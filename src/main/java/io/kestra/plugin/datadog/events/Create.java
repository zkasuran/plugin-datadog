package io.kestra.plugin.datadog.events;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Post an event to the Datadog event stream",
    description = "Creates an event, for example to mark a deployment or a pipeline run on the Datadog dashboards. Needs the API key only. " +
        "Datadog accepts the event for processing and answers right away: it can take a moment before the event shows in the stream."
)
@Plugin(
    examples = {
        @Example(
            title = "Mark the end of a pipeline run on the dashboards",
            full = true,
            code = """
                id: datadog_pipeline_event
                namespace: company.observability

                tasks:
                  - id: run_pipeline
                    type: io.kestra.plugin.core.log.Log
                    message: Loading the orders

                  - id: mark_run
                    type: io.kestra.plugin.datadog.events.Create
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    site: datadoghq.eu
                    title: "Orders pipeline finished"
                    text: "Execution {{ execution.id }} of {{ flow.id }} finished"
                    alertType: SUCCESS
                    tags:
                      - "env:prod"
                      - "flow:{{ flow.id }}"
                      - "source:kestra"
                """
        )
    }
)
public class Create extends AbstractDatadogTask implements RunnableTask<Create.Output> {
    // the limits of the Datadog events API
    private static final int MAX_TITLE = 500;
    private static final int MAX_TEXT = 4000;

    @Schema(title = "Event title", description = "The title of the event. At most 500 characters.")
    @NotNull
    private Property<String> title;

    @Schema(title = "Event text", description = "The body of the event. At most 4000 characters. Markdown is supported when the text starts with `%%% ` and ends with ` %%%`.")
    @NotNull
    private Property<String> text;

    @Schema(title = "Alert type", description = "How the event is shown. Datadog uses `INFO` when it is not set.")
    private Property<AlertType> alertType;

    @Schema(title = "Priority", description = "`NORMAL` or `LOW`. Datadog uses `NORMAL` when it is not set.")
    private Property<Priority> priority;

    @Schema(title = "Tags", description = "Tags to attach, such as `env:prod`.")
    private Property<List<String>> tags;

    @Schema(title = "Host", description = "The host the event is about.")
    private Property<String> host;

    @Schema(title = "Aggregation key", description = "Groups events with the same key in the event stream. At most 100 characters.")
    private Property<String> aggregationKey;

    @Schema(title = "Source type name", description = "The source of the event, such as `kestra`, to pick its icon.")
    private Property<String> sourceTypeName;

    @Schema(title = "Time of the event", description = "When the event happened, an ISO-8601 instant such as `2026-01-01T00:00:00Z`. Datadog uses the time it receives the event when it is not set.")
    private Property<Instant> dateHappened;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedTitle = runContext.render(this.title).as(String.class).orElse("").trim();
        if (renderedTitle.isEmpty()) {
            throw new IllegalArgumentException("The Datadog event `title` must not be blank");
        }
        if (renderedTitle.length() > MAX_TITLE) {
            throw new IllegalArgumentException("The Datadog event `title` has " + renderedTitle.length() + " characters, the limit is " + MAX_TITLE);
        }

        String renderedText = runContext.render(this.text).as(String.class).orElse("");
        if (renderedText.isBlank()) {
            throw new IllegalArgumentException("The Datadog event `text` must not be blank");
        }
        if (renderedText.length() > MAX_TEXT) {
            throw new IllegalArgumentException("The Datadog event `text` has " + renderedText.length() + " characters, the limit is " + MAX_TEXT);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", renderedTitle);
        body.put("text", renderedText);
        runContext.render(this.alertType).as(AlertType.class).ifPresent(value -> body.put("alert_type", value.api()));
        runContext.render(this.priority).as(Priority.class).ifPresent(value -> body.put("priority", value.api()));
        List<String> renderedTags = runContext.render(this.tags).asList(String.class);
        if (!renderedTags.isEmpty()) {
            body.put("tags", renderedTags);
        }
        runContext.render(this.host).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> body.put("host", value));
        runContext.render(this.aggregationKey).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> {
            if (value.length() > 100) {
                throw new IllegalArgumentException("The Datadog event `aggregationKey` has " + value.length() + " characters, the limit is 100");
            }
            body.put("aggregation_key", value);
        });
        runContext.render(this.sourceTypeName).as(String.class).filter(value -> !value.isBlank()).ifPresent(value -> body.put("source_type_name", value));
        runContext.render(this.dateHappened).as(Instant.class).ifPresent(value -> body.put("date_happened", value.getEpochSecond()));

        DatadogClient.Response response = client(runContext, false).post("/api/v1/events", body);

        Map<String, Object> answer = response.map();
        Object event = answer.get("event");
        String id = null;
        String url = null;
        if (event instanceof Map<?, ?> map) {
            id = map.get("id_str") != null ? String.valueOf(map.get("id_str")) : map.get("id") != null ? String.valueOf(map.get("id")) : null;
            url = map.get("url") == null ? null : String.valueOf(map.get("url"));
        }

        runContext.logger().info("Datadog accepted the event '{}'{}", renderedTitle, id == null ? "" : " (id " + id + ")");
        return Output.builder().eventId(id).url(url).status(answer.get("status") == null ? null : String.valueOf(answer.get("status"))).build();
    }

    public enum AlertType {
        ERROR("error"),
        WARNING("warning"),
        INFO("info"),
        SUCCESS("success"),
        USER_UPDATE("user_update"),
        RECOMMENDATION("recommendation"),
        SNAPSHOT("snapshot");

        private final String api;

        AlertType(String api) {
            this.api = api;
        }

        String api() {
            return api;
        }
    }

    public enum Priority {
        NORMAL("normal"),
        LOW("low");

        private final String api;

        Priority(String api) {
            this.api = api;
        }

        String api() {
            return api;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Event id", description = "The id of the event in Datadog, when Datadog returns it.")
        private final String eventId;

        @Schema(title = "Event URL", description = "The link to the event in Datadog, when Datadog returns it.")
        private final String url;

        @Schema(title = "Status", description = "The status Datadog answers, `ok` when the event is accepted.")
        private final String status;
    }
}
