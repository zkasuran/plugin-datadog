package io.kestra.plugin.datadog.monitors;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.StatefulTriggerService;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Start a flow when Datadog monitors change to an alerting state",
    description = """
        Polls the Datadog monitors and starts one execution with the monitors that entered one of the watched `states` since the previous poll. \
        Needs the API key and an application key with the `monitors_read` scope.

        A monitor is returned when its `overall_state` is one of `states` and its `overall_state_modified` is later than the previous poll, \
        so a monitor that stays in alert is returned once, not at every poll. The newest `overall_state_modified` seen is kept in the namespace KV store. \
        The first poll only records the current time: monitors that were already alerting are not returned. \
        A monitor that goes into alert and recovers between two polls is not seen, so keep `interval` shorter than the shortest outage you care about."""
)
@Plugin(
    examples = {
        @Example(
            title = "React to production monitors that go into alert",
            full = true,
            code = """
                id: datadog_on_alert
                namespace: company.observability

                triggers:
                  - id: on_alert
                    type: io.kestra.plugin.datadog.monitors.StateChange
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    monitorTags:
                      - "env:prod"
                    states:
                      - ALERT
                    interval: PT2M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} production monitor(s) went into alert: {{ trigger.monitors | jq('map(.name)') | first }}"
                """
        )
    }
)
public class StateChange extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<StateChange.Output> {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Builder.Default
    @Schema(title = "Interval between two polls", description = "The time between two polls of the Datadog API. Five minutes by default.", defaultValue = "PT5M")
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(title = "Datadog API key", description = "The organization API key. Use a Kestra secret.")
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(title = "Datadog application key", description = "An application key with the `monitors_read` scope. Use a Kestra secret.")
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> appKey;

    @Schema(title = "Datadog site", description = "The Datadog site of the organization, such as `datadoghq.com` (the default) or `datadoghq.eu`.")
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(title = "API base URL", description = "Replaces `https://api.<site>` when set, for a proxy or a test server.")
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    @Schema(title = "Name filter", description = "Watches the monitors whose name contains this text.")
    private Property<String> name;

    @Schema(title = "Tags filter", description = "Watches the monitors that carry all these tags.")
    private Property<List<String>> tags;

    @Schema(title = "Monitor tags filter", description = "Watches the monitors that carry all these monitor tags, such as `env:prod`.")
    private Property<List<String>> monitorTags;

    @Schema(title = "States to watch", description = "The monitor states that start an execution. `ALERT` only when it is not set.")
    private Property<List<State>> states;

    @Builder.Default
    @Schema(title = "Maximum number of monitors per poll", description = "Reads at most this many monitors from Datadog at each poll, between 1 and 10000. " +
        "Narrow the filters when the organization has more monitors than this.", defaultValue = "1000")
    private Property<Integer> maxMonitors = Property.ofValue(1000);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        int limit = runContext.render(this.maxMonitors).as(Integer.class).orElse(1000);
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxMonitors` must be between 1 and 10000 but was " + limit);
        }
        Set<State> watched = runContext.render(this.states).asList(State.class).isEmpty()
            ? Set.of(State.ALERT)
            : Set.copyOf(runContext.render(this.states).asList(State.class));
        Set<String> labels = watched.stream().map(State::label).collect(Collectors.toSet());

        DatadogClient client = DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, true);

        KVStore kvStore = runContext.namespaceKv(context.getNamespace());
        String stateKey = StatefulTriggerService.defaultKey(context.getNamespace(), context.getFlowId(), context.getTriggerId());

        Optional<Instant> cursor = readCursor(kvStore, stateKey);
        if (cursor.isEmpty()) {
            // first poll: remember the time, the monitors that already alert are not replayed
            Instant initial = Instant.now();
            writeCursor(kvStore, stateKey, initial);
            logger.info("No cursor found, watching Datadog monitors that change state after {}", initial);
            return Optional.empty();
        }

        Map<String, Object> params = DatadogClient.query();
        runContext.render(this.name).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> params.put("name", value));
        Monitors.join(runContext.render(this.tags).asList(String.class)).ifPresent(value -> params.put("tags", value));
        Monitors.join(runContext.render(this.monitorTags).asList(String.class)).ifPresent(value -> params.put("monitor_tags", value));

        List<Map<String, Object>> all;
        try {
            all = Monitors.collect(client, params, limit);
        } catch (DatadogClient.DatadogException e) {
            // a rate limit or an outage at Datadog must not stop the trigger: the next poll reads the same window again
            if (e.getStatus() == 429 || e.getStatus() >= 500) {
                logger.warn("The poll is skipped and will be retried on the next interval: {}", e.getMessage());
                return Optional.empty();
            }
            throw e;
        }

        List<Map<String, Object>> changed = new ArrayList<>();
        Instant newest = cursor.get();
        for (Map<String, Object> monitor : all) {
            if (!labels.contains(String.valueOf(monitor.get("overall_state")))) {
                continue;
            }
            Instant modified = parseInstant(monitor.get("overall_state_modified"));
            if (modified != null && modified.isAfter(cursor.get())) {
                changed.add(monitor);
                if (modified.isAfter(newest)) {
                    newest = modified;
                }
            }
        }
        if (changed.isEmpty()) {
            return Optional.empty();
        }
        changed.sort(Comparator.comparing(monitor -> parseInstant(monitor.get("overall_state_modified"))));

        Output output = Output.builder().monitors(changed).count(changed.size()).build();

        // at-least-once: the cursor moves once the execution is built. If building fails, the next poll returns the same monitors.
        Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
        writeCursor(kvStore, stateKey, newest);
        logger.info("{} Datadog monitor(s) entered {}", changed.size(), labels);
        return Optional.of(execution);
    }

    static Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // some answers carry no offset: read them as UTC
        }
        try {
            return Instant.parse(text.endsWith("Z") ? text : text + "Z");
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Optional<Instant> readCursor(KVStore kvStore, String key) throws Exception {
        Optional<KVValue> stored = kvStore.getValue(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Object value = stored.get().value();
        Map<String, Object> json = switch (value) {
            case byte[] bytes -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case String text -> JacksonMapper.ofJson().readValue(text, MAP_TYPE);
            case Map<?, ?> map -> JacksonMapper.ofJson().convertValue(map, MAP_TYPE);
            default -> throw new IllegalStateException("The Datadog trigger cursor '" + key + "' has an unexpected type: " + value);
        };
        return Optional.of(Instant.parse(String.valueOf(json.get("modified"))));
    }

    private static void writeCursor(KVStore kvStore, String key, Instant modified) throws Exception {
        byte[] bytes = JacksonMapper.ofJson().writeValueAsString(Map.of("modified", modified.toString())).getBytes(StandardCharsets.UTF_8);
        // no expiration: an expired cursor would replay or skip alerts
        kvStore.put(key, new KVValueAndMetadata(new KVMetadata("Datadog StateChange trigger cursor", (Duration) null), bytes));
    }

    public enum State {
        ALERT("Alert"),
        WARN("Warn"),
        NO_DATA("No Data"),
        OK("OK"),
        IGNORED("Ignored"),
        SKIPPED("Skipped"),
        UNKNOWN("Unknown");

        private final String label;

        State(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitors", description = "The monitors that changed state, from the oldest change to the newest. Use `{{ trigger.monitors }}`.")
        private final List<Map<String, Object>> monitors;

        @Schema(title = "Number of monitors")
        private final Integer count;
    }
}
