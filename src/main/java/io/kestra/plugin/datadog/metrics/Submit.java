package io.kestra.plugin.datadog.metrics;

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

import java.time.Instant;
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
    title = "Submit a custom metric point to Datadog",
    description = """
        Sends a gauge, a count or a rate to the Datadog metrics intake. Needs the API key only.

        Two ways to describe the data. Give `metric` and `value` for one point or give `series` to send several series in one call, \
        in the shape of the Datadog `POST /api/v2/series` body. A point without `timestamp` gets the time the task runs. Giving both or neither, is an error.

        Datadog accepts a point up to 1 hour in the future and up to 1 hour in the past and answers before the point is processed: \
        a point it rejects later does not make the task fail."""
)
@Plugin(
    examples = {
        @Example(
            title = "Report the number of rows a pipeline loaded",
            full = true,
            code = """
                id: datadog_row_count
                namespace: company.observability

                tasks:
                  - id: report_rows
                    type: io.kestra.plugin.datadog.metrics.Submit
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    metric: kestra.orders.rows_loaded
                    value: 1250
                    metricType: GAUGE
                    tags:
                      - "env:prod"
                      - "flow:{{ flow.id }}"
                """
        ),
        @Example(
            title = "Send two series in one call",
            full = true,
            code = """
                id: datadog_two_series
                namespace: company.observability

                tasks:
                  - id: report
                    type: io.kestra.plugin.datadog.metrics.Submit
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    series:
                      - metric: kestra.orders.rows_loaded
                        type: 3
                        points:
                          - value: 1250
                        tags: ["env:prod"]
                      - metric: kestra.orders.rows_rejected
                        type: 3
                        points:
                          - value: 3
                        tags: ["env:prod"]
                """
        )
    }
)
public class Submit extends AbstractDatadogTask implements RunnableTask<Submit.Output> {
    @Schema(title = "Metric name", description = "The name of the metric, such as `kestra.orders.rows_loaded`. Use with `value`.")
    private Property<String> metric;

    @Schema(title = "Metric value", description = "The value of the point. Use with `metric`.")
    private Property<Double> value;

    @Schema(title = "Metric type", description = "`GAUGE` (default), `COUNT` or `RATE`. With `RATE`, set `interval` to the length of the period the value covers.")
    private Property<MetricType> metricType;

    @Schema(title = "Time of the point", description = "When the value was measured, an ISO-8601 instant. The time the task runs when it is not set.")
    private Property<Instant> timestamp;

    @Schema(title = "Tags", description = "Tags to attach to the series, such as `env:prod`.")
    private Property<List<String>> tags;

    @Schema(title = "Host", description = "The host the series is reported for. Sent as a resource of type `host`.")
    private Property<String> host;

    @Schema(title = "Unit", description = "The unit of the metric, such as `row`.")
    private Property<String> unit;

    @Schema(title = "Interval", description = "The length in seconds of the period a `COUNT` or `RATE` value covers.")
    private Property<Long> interval;

    @Schema(
        title = "Series",
        description = "A list of series in the shape of the Datadog `POST /api/v2/series` body (`metric`, `type`, `points`, `tags`, `resources`, `unit`, `interval`). " +
            "`type` is a number there: 1 count, 2 rate, 3 gauge. Use instead of `metric` and `value`."
    )
    private Property<List<Map<String, Object>>> series;

    @Override
    public Output run(RunContext runContext) throws Exception {
        boolean single = this.metric != null || this.value != null;
        boolean multiple = this.series != null;
        if (single == multiple) {
            throw new IllegalArgumentException("Give either `metric` and `value` for one point or `series` for several, but not both and not neither");
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        int points = 0;
        if (multiple) {
            for (Map<String, Object> entry : runContext.render(this.series).asList(Map.class)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) entry;
                if (item.get("metric") == null || String.valueOf(item.get("metric")).isBlank()) {
                    throw new IllegalArgumentException("Every entry of `series` needs a `metric` name");
                }
                if (!(item.get("points") instanceof List<?> list) || list.isEmpty()) {
                    throw new IllegalArgumentException("The series '" + item.get("metric") + "' needs at least one entry in `points`");
                }
                // a point without a timestamp is measured now
                long now = Instant.now().getEpochSecond();
                List<Map<String, Object>> filled = new ArrayList<>();
                for (Object point : list) {
                    if (!(point instanceof Map<?, ?> raw) || raw.get("value") == null) {
                        throw new IllegalArgumentException("Every entry of `points` of the series '" + item.get("metric") + "' needs a `value`");
                    }
                    Map<String, Object> copy = new LinkedHashMap<>();
                    raw.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    copy.putIfAbsent("timestamp", now);
                    filled.add(copy);
                }
                Map<String, Object> withPoints = new LinkedHashMap<>(item);
                withPoints.put("points", filled);
                points += filled.size();
                payload.add(withPoints);
            }
            if (payload.isEmpty()) {
                throw new IllegalArgumentException("`series` must contain at least one series");
            }
        } else {
            String renderedMetric = runContext.render(this.metric).as(String.class).orElse("").trim();
            if (renderedMetric.isEmpty()) {
                throw new IllegalArgumentException("The Datadog `metric` must not be blank");
            }
            Double renderedValue = runContext.render(this.value).as(Double.class).orElse(null);
            if (renderedValue == null || renderedValue.isNaN() || renderedValue.isInfinite()) {
                throw new IllegalArgumentException("The Datadog `value` must be a finite number but was " + renderedValue);
            }
            Instant at = runContext.render(this.timestamp).as(Instant.class).orElseGet(Instant::now);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", renderedMetric);
            item.put("type", runContext.render(this.metricType).as(MetricType.class).orElse(MetricType.GAUGE).code());
            item.put("points", List.of(Map.of("timestamp", at.getEpochSecond(), "value", renderedValue)));
            List<String> renderedTags = runContext.render(this.tags).asList(String.class);
            if (!renderedTags.isEmpty()) {
                item.put("tags", renderedTags);
            }
            runContext.render(this.host).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> item.put("resources", List.of(Map.of("name", v, "type", "host"))));
            runContext.render(this.unit).as(String.class).filter(v -> !v.isBlank()).ifPresent(v -> item.put("unit", v));
            runContext.render(this.interval).as(Long.class).ifPresent(v -> item.put("interval", v));
            payload.add(item);
            points = 1;
        }

        client(runContext, false).post("/api/v2/series", Map.of("series", payload));

        runContext.logger().info("Datadog accepted {} point(s) in {} series", points, payload.size());
        return Output.builder().series(payload.size()).points(points).build();
    }

    public enum MetricType {
        COUNT(1),
        RATE(2),
        GAUGE(3);

        private final int code;

        MetricType(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Series sent", description = "The number of series in the request.")
        private final Integer series;

        @Schema(title = "Points sent", description = "The number of points in the request.")
        private final Integer points;
    }
}
