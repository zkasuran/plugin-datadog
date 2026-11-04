package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Search the Datadog logs",
    description = """
        Returns the log events that match a Log Explorer query in a time window. \
        Needs the API key and an application key with the `logs_read_data` scope.

        The events are read page by page until `maxResults` are collected or Datadog has no more. \
        Datadog applies a rate limit of 300 requests per hour to this endpoint."""
)
@Plugin(
    examples = {
        @Example(
            title = "Fail the flow when a service logged errors in the last 15 minutes",
            full = true,
            code = """
                id: datadog_error_logs
                namespace: company.observability

                tasks:
                  - id: errors
                    type: io.kestra.plugin.datadog.logs.Search
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    query: "service:orders status:error"
                    from: now-15m
                    maxResults: 50

                  - id: gate
                    type: io.kestra.plugin.core.execution.Assert
                    errorMessage: "{{ outputs.errors.size }} error log(s) from orders"
                    conditions:
                      - "{{ outputs.errors.size == 0 }}"
                """
        )
    }
)
public class Search extends AbstractDatadogTask implements RunnableTask<Search.Output> {
    private static final int PAGE_SIZE = 1000;

    @Builder.Default
    @Schema(title = "Query", description = "A Log Explorer query, such as `service:orders status:error`. All the logs when it is not set.", defaultValue = "*")
    private Property<String> query = Property.ofValue("*");

    @Builder.Default
    @Schema(title = "From", description = "The start of the window: a relative time such as `now-15m` or `now-1d`, an ISO-8601 date or a timestamp in milliseconds.", defaultValue = "now-15m")
    private Property<String> from = Property.ofValue("now-15m");

    @Builder.Default
    @Schema(title = "To", description = "The end of the window, in the same formats as `from`.", defaultValue = "now")
    private Property<String> to = Property.ofValue("now");

    @Schema(title = "Indexes", description = "The log indexes to search. All the indexes when it is not set.")
    private Property<List<String>> indexes;

    @Schema(title = "Sort order", description = "`ASCENDING` (oldest first) or `DESCENDING` (newest first). Datadog uses `DESCENDING` when it is not set.")
    private Property<Sort> sort;

    @Builder.Default
    @Schema(title = "Maximum number of results", description = "Stops after this many log events, between 1 and 10000.", defaultValue = "100")
    private Property<Integer> maxResults = Property.ofValue(100);

    @Override
    public Output run(RunContext runContext) throws Exception {
        int limit = runContext.render(this.maxResults).as(Integer.class).orElse(100);
        if (limit < 1 || limit > 10_000) {
            throw new IllegalArgumentException("The Datadog `maxResults` must be between 1 and 10000 but was " + limit);
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("query", runContext.render(this.query).as(String.class).filter(v -> !v.isBlank()).orElse("*"));
        filter.put("from", runContext.render(this.from).as(String.class).filter(v -> !v.isBlank()).orElse("now-15m"));
        filter.put("to", runContext.render(this.to).as(String.class).filter(v -> !v.isBlank()).orElse("now"));
        List<String> renderedIndexes = runContext.render(this.indexes).asList(String.class);
        if (!renderedIndexes.isEmpty()) {
            filter.put("indexes", renderedIndexes);
        }
        Sort renderedSort = runContext.render(this.sort).as(Sort.class).orElse(null);

        var client = client(runContext, true);
        List<Map<String, Object>> logs = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("limit", Math.min(PAGE_SIZE, limit - logs.size()));
            if (cursor != null) {
                page.put("cursor", cursor);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("filter", filter);
            body.put("page", page);
            if (renderedSort != null) {
                body.put("sort", renderedSort.api());
            }

            Map<String, Object> answer = client.post("/api/v2/logs/events/search", body).map();
            if (answer.get("data") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> raw && logs.size() < limit) {
                        logs.add(flatten(raw));
                    }
                }
            }
            cursor = null;
            if (logs.size() < limit && answer.get("meta") instanceof Map<?, ?> meta && meta.get("page") instanceof Map<?, ?> metaPage && metaPage.get("after") != null) {
                cursor = String.valueOf(metaPage.get("after"));
            }
        } while (cursor != null);

        runContext.logger().info("Datadog returned {} log event(s)", logs.size());
        return Output.builder().logs(logs).size(logs.size()).build();
    }

    /** Puts the id and the attributes of an event side by side: {@code id}, {@code timestamp}, {@code host}, {@code service}, {@code status}, {@code message}, {@code tags} and {@code attributes}. */
    private static Map<String, Object> flatten(Map<?, ?> event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.get("id"));
        if (event.get("attributes") instanceof Map<?, ?> attributes) {
            attributes.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }

    public enum Sort {
        ASCENDING("timestamp"),
        DESCENDING("-timestamp");

        private final String api;

        Sort(String api) {
            this.api = api;
        }

        String api() {
            return api;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Log events", description = "The events: `id`, `timestamp`, `host`, `service`, `status`, `message`, `tags` and `attributes` (the parsed fields of the log).")
        private final List<Map<String, Object>> logs;

        @Schema(title = "Number of events")
        private final Integer size;
    }
}
