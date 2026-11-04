package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitTest extends AbstractDatadogTest {
    private Submit.SubmitBuilder<?, ?> task() {
        server.reply("POST", "/api/v2/series", 202, "{\"errors\":[]}");
        return Submit.builder()
            .id("metric")
            .type(Submit.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .apiUrl(Property.ofValue(server.url()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlySeries(MockDatadogServer.Received request) throws Exception {
        List<Map<String, Object>> series = (List<Map<String, Object>>) request.json().get("series");
        assertEquals(1, series.size());
        return series.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitsOneGaugePoint() throws Exception {
        Submit.Output output = task()
            .metric(Property.ofValue("kestra.orders.rows_loaded"))
            .value(Property.ofValue(1250.0))
            .timestamp(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z")))
            .tags(Property.ofValue(List.of("env:prod")))
            .host(Property.ofValue("worker-1"))
            .unit(Property.ofValue("row"))
            .build().run(runContext());

        MockDatadogServer.Received request = server.only();
        assertEquals("/api/v2/series", request.path());
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertNull(request.header("DD-APPLICATION-KEY"));

        Map<String, Object> series = onlySeries(request);
        assertEquals("kestra.orders.rows_loaded", series.get("metric"));
        assertEquals(3, series.get("type"), "GAUGE is 3");
        assertEquals(List.of("env:prod"), series.get("tags"));
        assertEquals("row", series.get("unit"));
        assertEquals(List.of(Map.of("name", "worker-1", "type", "host")), series.get("resources"));
        Map<String, Object> point = ((List<Map<String, Object>>) series.get("points")).get(0);
        assertEquals(1767225600, ((Number) point.get("timestamp")).longValue());
        assertEquals(1250.0, ((Number) point.get("value")).doubleValue());

        assertEquals(1, output.getSeries());
        assertEquals(1, output.getPoints());
    }

    @Test
    void typesMapToDatadogCodes() throws Exception {
        task().metric(Property.ofValue("m")).value(Property.ofValue(1.0)).metricType(Property.ofValue(Submit.MetricType.COUNT))
            .interval(Property.ofValue(10L)).build().run(runContext());
        assertEquals(1, onlySeries(server.only()).get("type"));
        assertEquals(10, ((Number) onlySeries(server.only()).get("interval")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPointWithoutATimestampGetsTheCurrentTime() throws Exception {
        long before = Instant.now().getEpochSecond();
        task().metric(Property.ofValue("m")).value(Property.ofValue(2.0)).build().run(runContext());
        long sent = ((Number) ((List<Map<String, Object>>) onlySeries(server.only()).get("points")).get(0).get("timestamp")).longValue();
        assertTrue(sent >= before && sent <= Instant.now().getEpochSecond());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitsSeveralSeriesAndFillsMissingTimestamps() throws Exception {
        Submit.Output output = task().series(Property.ofValue(List.of(
            Map.of("metric", "a", "type", 3, "points", List.of(Map.of("value", 1), Map.of("value", 2, "timestamp", 1767225600)), "tags", List.of("env:prod")),
            Map.of("metric", "b", "type", 1, "points", List.of(Map.of("value", 5)))
        ))).build().run(runContext());

        List<Map<String, Object>> series = (List<Map<String, Object>>) server.only().json().get("series");
        assertEquals(2, series.size());
        List<Map<String, Object>> first = (List<Map<String, Object>>) series.get(0).get("points");
        assertTrue(first.get(0).containsKey("timestamp"), "the missing timestamp is filled in");
        assertEquals(1767225600, ((Number) first.get(1).get("timestamp")).longValue(), "a given timestamp is kept");
        assertEquals(2, output.getSeries());
        assertEquals(3, output.getPoints());
    }

    @Test
    void refusesInvalidInputBeforeSending() {
        // neither, both
        assertThrows(IllegalArgumentException.class, () -> task().build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().metric(Property.ofValue("m")).value(Property.ofValue(1.0))
            .series(Property.ofValue(List.of(Map.of("metric", "a", "points", List.of(Map.of("value", 1)))))).build().run(runContext()));
        // a metric without a value, a bad value, a blank name
        assertThrows(IllegalArgumentException.class, () -> task().metric(Property.ofValue("m")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().metric(Property.ofValue("m")).value(Property.ofValue(Double.NaN)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().metric(Property.ofValue("m")).value(Property.ofValue(Double.POSITIVE_INFINITY)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().metric(Property.ofValue(" ")).value(Property.ofValue(1.0)).build().run(runContext()));
        // series entries that Datadog would reject
        assertThrows(IllegalArgumentException.class, () -> task().series(Property.ofValue(List.of())).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().series(Property.ofValue(List.of(Map.of("points", List.of(Map.of("value", 1)))))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().series(Property.ofValue(List.of(Map.of("metric", "a")))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().series(Property.ofValue(List.of(Map.of("metric", "a", "points", List.of(Map.of("timestamp", 1)))))).build().run(runContext()));
        assertTrue(server.requests.isEmpty(), "nothing is sent for invalid input");
    }

    @Test
    void anErrorAnswerFailsTheTask() {
        server.reply("POST", "/api/v2/series", 400, "{\"errors\":[\"bad payload\"]}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> Submit.builder().id("m").type(Submit.class.getName()).apiKey(Property.ofValue(API_KEY)).apiUrl(Property.ofValue(server.url()))
                .metric(Property.ofValue("m")).value(Property.ofValue(1.0)).build().run(runContext()));
        assertTrue(error.getMessage().contains("bad payload"), error.getMessage());
    }
}
