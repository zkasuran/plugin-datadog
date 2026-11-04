package io.kestra.plugin.datadog.metrics;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTest extends AbstractDatadogTest {
    private Query.QueryBuilder<?, ?> task() {
        return Query.builder()
            .id("query")
            .type(Query.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()))
            .query(Property.ofValue("avg:system.cpu.user{env:prod} by {host}"));
    }

    @Test
    void returnsTheSeriesAndSendsBothKeys() throws Exception {
        server.reply("GET", "/api/v1/query", 200, """
            {"status":"ok","series":[
              {"metric":"system.cpu.user","scope":"host:a","pointlist":[[1767225600000,1.5],[1767225660000,2.5]]},
              {"metric":"system.cpu.user","scope":"host:b","pointlist":[[1767225600000,3.5]]}]}""");

        Query.Output output = task()
            .from(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z")))
            .to(Property.ofValue(Instant.parse("2026-01-01T01:00:00Z")))
            .build().run(runContext());

        MockDatadogServer.Received request = server.only();
        assertEquals("GET", request.method());
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
        String query = URLDecoder.decode(request.query(), StandardCharsets.UTF_8);
        assertTrue(query.contains("from=1767225600"), query);
        assertTrue(query.contains("to=1767229200"), query);
        assertTrue(query.contains("query=avg:system.cpu.user{env:prod} by {host}"), query);

        assertEquals("ok", output.getStatus());
        assertEquals(2, output.getSeriesCount());
        assertEquals(3, output.getPointCount());
        assertEquals("host:a", output.getSeries().get(0).get("scope"));
    }

    @Test
    void lookbackSetsTheWindowFromTheEnd() throws Exception {
        server.reply("GET", "/api/v1/query", 200, "{\"status\":\"ok\",\"series\":[]}");

        Query.Output output = task()
            .to(Property.ofValue(Instant.parse("2026-01-01T01:00:00Z")))
            .lookback(Property.ofValue(Duration.ofMinutes(15)))
            .build().run(runContext());

        String query = URLDecoder.decode(server.only().query(), StandardCharsets.UTF_8);
        assertTrue(query.contains("from=1767228300"), query);
        assertEquals(0, output.getPointCount(), "no data is not an error");
        assertEquals(0, output.getSeriesCount());
    }

    @Test
    void aQueryDatadogCannotRunFailsTheTask() {
        server.reply("GET", "/api/v1/query", 200, "{\"status\":\"error\",\"error\":\"Error parsing query\"}");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));

        assertTrue(error.getMessage().contains("Error parsing query"), error.getMessage());
    }

    @Test
    void refusesAnEmptyWindowOrQuery() {
        assertThrows(IllegalArgumentException.class, () -> task().query(Property.ofValue(" ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task()
            .from(Property.ofValue(Instant.parse("2026-01-01T01:00:00Z"))).to(Property.ofValue(Instant.parse("2026-01-01T01:00:00Z")))
            .build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }

    @Test
    void theApplicationKeyIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> Query.builder().id("q").type(Query.class.getName())
            .apiKey(Property.ofValue(API_KEY)).apiUrl(Property.ofValue(server.url())).query(Property.ofValue("avg:x{*}"))
            .build().run(runContext()));
        assertTrue(server.requests.isEmpty());
    }
}
