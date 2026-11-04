package io.kestra.plugin.datadog.metrics;

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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query timeseries metrics from Datadog",
    description = """
        Runs a Datadog metrics query over a time window and returns the matching series with their points. \
        Needs the API key and an application key with the `timeseries_query` scope.

        The window is `from` to `to` or the last `lookback` when `from` is not set. Datadog returns at most about 300 points per series \
        and picks the rollup itself, so a long window returns coarser points: add `.rollup(avg, 300)` to the query to choose it."""
)
@Plugin(
    examples = {
        @Example(
            title = "Fail the flow when the error rate of a service is too high",
            full = true,
            code = """
                id: datadog_error_rate_gate
                namespace: company.observability

                tasks:
                  - id: error_rate
                    type: io.kestra.plugin.datadog.metrics.Query
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    query: "avg:trace.http.request.errors{service:orders}.as_rate()"
                    lookback: PT15M

                  - id: check
                    type: io.kestra.plugin.core.execution.Assert
                    errorMessage: "No data returned for the orders error rate"
                    conditions:
                      - "{{ outputs.error_rate.pointCount > 0 }}"
                """
        )
    }
)
public class Query extends AbstractDatadogTask implements RunnableTask<Query.Output> {
    @Schema(title = "Metrics query", description = "A Datadog metrics query, such as `avg:system.cpu.user{env:prod} by {host}`.")
    @NotNull
    private Property<String> query;

    @Schema(title = "Start of the window", description = "An ISO-8601 instant. When not set, the window starts `lookback` before `to`.")
    private Property<Instant> from;

    @Schema(title = "End of the window", description = "An ISO-8601 instant. The time the task runs when it is not set.")
    private Property<Instant> to;

    @Schema(title = "Look-back", description = "The length of the window when `from` is not set. One hour when it is not set.", defaultValue = "PT1H")
    private Property<Duration> lookback;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedQuery = runContext.render(this.query).as(String.class).orElse("").trim();
        if (renderedQuery.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `query` must not be blank");
        }

        Instant end = runContext.render(this.to).as(Instant.class).orElseGet(Instant::now);
        Duration window = runContext.render(this.lookback).as(Duration.class).orElse(Duration.ofHours(1));
        Instant start = runContext.render(this.from).as(Instant.class).orElseGet(() -> end.minus(window));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("The start of the window (" + start + ") must be before its end (" + end + ")");
        }

        Map<String, Object> params = DatadogClient.query();
        params.put("from", start.getEpochSecond());
        params.put("to", end.getEpochSecond());
        params.put("query", renderedQuery);

        Map<String, Object> answer = client(runContext, true).get("/api/v1/query", params).map();

        // a query Datadog cannot run is answered with a 200 and `status: error`
        String status = answer.get("status") == null ? null : String.valueOf(answer.get("status"));
        if ("error".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Datadog could not run the query '" + renderedQuery + "': " + answer.getOrDefault("error", answer.get("message")));
        }

        List<Map<String, Object>> series = new ArrayList<>();
        int pointCount = 0;
        if (answer.get("series") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = (Map<String, Object>) raw;
                    series.add(item);
                    if (item.get("pointlist") instanceof List<?> points) {
                        pointCount += points.size();
                    }
                }
            }
        }

        runContext.logger().info("Datadog returned {} series and {} points for '{}'", series.size(), pointCount, renderedQuery);
        return Output.builder().status(status).series(series).seriesCount(series.size()).pointCount(pointCount).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Status", description = "The status Datadog answers, `ok` when the query ran.")
        private final String status;

        @Schema(title = "Series", description = "The series returned. Each has `metric`, `scope`, `expression`, `unit` and `pointlist`, a list of `[timestamp in milliseconds, value]` pairs.")
        private final List<Map<String, Object>> series;

        @Schema(title = "Number of series")
        private final Integer seriesCount;

        @Schema(title = "Number of points", description = "The points of all the series. 0 means the query matched no data in the window.")
        private final Integer pointCount;
    }
}
