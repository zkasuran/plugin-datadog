package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Datadog monitors",
    description = """
        Lists the monitors of the organization, with their `overall_state` and filters them by name, tag or group state. \
        Needs the API key and an application key with the `monitors_read` scope.

        The monitors are read page by page until `maxMonitors` are collected or Datadog has no more. \
        The filters are applied by Datadog, so `groupStates` selects the monitors that have a group in one of those states, \
        which is not always the `overall_state` of the monitor: check `overall_state` in the output when it matters."""
)
@Plugin(
    examples = {
        @Example(
            title = "Check that no production monitor is in alert before a deployment",
            full = true,
            code = """
                id: datadog_pre_deploy_check
                namespace: company.observability

                tasks:
                  - id: alerting
                    type: io.kestra.plugin.datadog.monitors.List
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    monitorTags:
                      - "env:prod"
                    groupStates:
                      - alert

                  - id: gate
                    type: io.kestra.plugin.core.execution.Assert
                    errorMessage: "{{ outputs.alerting.size }} production monitor(s) are in alert"
                    conditions:
                      - "{{ outputs.alerting.size == 0 }}"
                """
        )
    }
)
public class List extends AbstractDatadogTask implements RunnableTask<List.Output> {
    private static final int DEFAULT_MAX = 1000;

    @Schema(title = "Name filter", description = "Returns the monitors whose name contains this text.")
    private Property<String> name;

    @Schema(title = "Tags filter", description = "Returns the monitors that carry all these tags. Tags set on the monitor, for example `service:orders`.")
    private Property<java.util.List<String>> tags;

    @Schema(title = "Monitor tags filter", description = "Returns the monitors that carry all these monitor tags (the tags in the monitor definition, not the ones that come from the scope), for example `env:prod`.")
    private Property<java.util.List<String>> monitorTags;

    @Schema(title = "Group states filter", description = "Returns the monitors that have a group in one of these states: `alert`, `warn`, `no data` or `ok`.")
    private Property<java.util.List<String>> groupStates;

    @Schema(title = "Maximum number of monitors", description = "Stops after this many monitors, between 1 and 10000.", defaultValue = "1000")
    private Property<Integer> maxMonitors;

    @Override
    public Output run(RunContext runContext) throws Exception {
        int limit = runContext.render(this.maxMonitors).as(Integer.class).orElse(DEFAULT_MAX);
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxMonitors` must be between 1 and 10000 but was " + limit);
        }

        Map<String, Object> params = DatadogClient.query();
        runContext.render(this.name).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> params.put("name", value));
        Monitors.join(runContext.render(this.tags).asList(String.class)).ifPresent(value -> params.put("tags", value));
        Monitors.join(runContext.render(this.monitorTags).asList(String.class)).ifPresent(value -> params.put("monitor_tags", value));
        Monitors.join(runContext.render(this.groupStates).asList(String.class)).ifPresent(value -> params.put("group_states", value));

        java.util.List<Map<String, Object>> monitors = Monitors.collect(client(runContext, true), params, limit);

        runContext.logger().info("Datadog returned {} monitor(s)", monitors.size());
        return Output.builder().monitors(monitors).size(monitors.size()).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Monitors", description = "The monitors as Datadog returns them: `id`, `name`, `type`, `query`, `overall_state`, `overall_state_modified`, `tags`, `message` and `options`.")
        private final java.util.List<Map<String, Object>> monitors;

        @Schema(title = "Number of monitors")
        private final Integer size;
    }
}
