package io.kestra.plugin.datadog.logs;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogsTest extends AbstractDatadogTest {
    private Send.SendBuilder<?, ?> send() {
        server.reply("POST", "/api/v2/logs", 202, "{}");
        return Send.builder().id("send").type(Send.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .intakeUrl(Property.ofValue(server.url()));
    }

    private Search.SearchBuilder<?, ?> search() {
        return Search.builder().id("search").type(Search.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()));
    }

    private static String events(int from, int count, String after) {
        String data = IntStream.range(from, from + count)
            .mapToObj(i -> "{\"id\":\"e" + i + "\",\"type\":\"log\",\"attributes\":{\"message\":\"line " + i + "\",\"service\":\"orders\",\"status\":\"error\",\"attributes\":{\"n\":" + i + "}}}")
            .collect(Collectors.joining(",", "[", "]"));
        return "{\"data\":" + data + (after == null ? "" : ",\"meta\":{\"page\":{\"after\":\"" + after + "\"}}") + "}";
    }

    @Test
    void sendsOneEntryToTheIntakeWithTheApiKeyOnly() throws Exception {
        Send.Output output = send()
            .message(Property.ofValue("Loaded 1250 orders"))
            .service(Property.ofValue("orders-pipeline"))
            .source(Property.ofValue("kestra"))
            .host(Property.ofValue("worker-1"))
            .tags(Property.ofValue(List.of("env:prod", "team:data")))
            .attributes(Property.ofValue(Map.of("rows", 1250, "message", "attribute cannot replace the message")))
            .build().run(runContext());

        MockDatadogServer.Received request = server.only();
        assertEquals("/api/v2/logs", request.path());
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertNull(request.header("DD-APPLICATION-KEY"));
        List<?> body = request.jsonList();
        assertEquals(1, body.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) body.get(0);
        assertEquals("Loaded 1250 orders", entry.get("message"));
        assertEquals("orders-pipeline", entry.get("service"));
        assertEquals("kestra", entry.get("ddsource"));
        assertEquals("worker-1", entry.get("hostname"));
        assertEquals("env:prod,team:data", entry.get("ddtags"));
        assertEquals(1250, entry.get("rows"));
        assertEquals(1, output.getSent());
    }

    @Test
    void sendsSeveralEntries() throws Exception {
        Send.Output output = send().logs(Property.ofValue(List.of(
            Map.of("message", "a", "service", "s"), Map.of("message", "b", "ddtags", "env:prod")))).build().run(runContext());

        assertEquals(2, server.only().jsonList().size());
        assertEquals(2, output.getSent());
    }

    @Test
    void refusesInvalidEntries() {
        assertThrows(IllegalArgumentException.class, () -> send().build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> send().message(Property.ofValue("a")).logs(Property.ofValue(List.of(Map.of("message", "b")))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> send().message(Property.ofValue("  ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> send().logs(Property.ofValue(List.of())).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> send().logs(Property.ofValue(List.of(Map.of("service", "s")))).build().run(runContext()));
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(Map.of("message", "m" + i));
        }
        assertThrows(IllegalArgumentException.class, () -> send().logs(Property.ofValue(tooMany)).build().run(runContext()));
        assertTrue(server.requests.isEmpty(), "nothing is sent for invalid entries");
    }

    @Test
    void anIntakeErrorFailsTheTask() {
        server.reply("POST", "/api/v2/logs", 403, "{\"errors\":[{\"code\":\"forbidden\",\"detail\":\"API key invalid\"}]}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> Send.builder().id("s").type(Send.class.getName()).apiKey(Property.ofValue(API_KEY)).intakeUrl(Property.ofValue(server.url()))
                .message(Property.ofValue("m")).build().run(runContext()));
        assertTrue(error.getMessage().contains("403"), error.getMessage());
    }

    @Test
    void searchSendsTheQueryAndFlattensTheEvents() throws Exception {
        server.reply("POST", "/api/v2/logs/events/search", 200, events(1, 2, null));

        Search.Output output = search()
            .query(Property.ofValue("service:orders status:error"))
            .from(Property.ofValue("now-1h"))
            .indexes(Property.ofValue(List.of("main")))
            .sort(Property.ofValue(Search.Sort.ASCENDING))
            .maxResults(Property.ofValue(50))
            .build().run(runContext());

        MockDatadogServer.Received request = server.only();
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
        Map<String, Object> body = request.json();
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) body.get("filter");
        assertEquals("service:orders status:error", filter.get("query"));
        assertEquals("now-1h", filter.get("from"));
        assertEquals("now", filter.get("to"));
        assertEquals(List.of("main"), filter.get("indexes"));
        assertEquals("timestamp", body.get("sort"));
        assertEquals(50, ((Map<?, ?>) body.get("page")).get("limit"));

        assertEquals(2, output.getSize());
        assertEquals("e1", output.getLogs().get(0).get("id"));
        assertEquals("line 1", output.getLogs().get(0).get("message"));
        assertEquals("orders", output.getLogs().get(0).get("service"));
    }

    @Test
    void searchFollowsTheCursorUntilMaxResults() throws Exception {
        server.reply("POST", "/api/v2/logs/events/search", 200, events(0, 1000, "cursor-1"));
        server.reply("POST", "/api/v2/logs/events/search", 200, events(1000, 1000, "cursor-2"));

        Search.Output output = search().maxResults(Property.ofValue(1500)).build().run(runContext());

        assertEquals(1500, output.getSize());
        assertEquals(2, server.requests.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> secondPage = (Map<String, Object>) server.requests.get(1).json().get("page");
        assertEquals("cursor-1", secondPage.get("cursor"));
        assertEquals(500, secondPage.get("limit"), "the second page asks only for what is missing");
    }

    @Test
    void searchStopsWhenDatadogHasNoMore() throws Exception {
        server.reply("POST", "/api/v2/logs/events/search", 200, events(0, 3, null));

        assertEquals(3, search().maxResults(Property.ofValue(500)).build().run(runContext()).getSize());
        assertEquals(1, server.requests.size());
    }

    @Test
    void searchDefaultsAndLimits() throws Exception {
        server.reply("POST", "/api/v2/logs/events/search", 200, "{\"data\":[]}");
        assertEquals(0, search().build().run(runContext()).getSize());
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) server.only().json().get("filter");
        assertEquals("*", filter.get("query"));
        assertEquals("now-15m", filter.get("from"));
        assertThrows(IllegalArgumentException.class, () -> search().maxResults(Property.ofValue(0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> search().maxResults(Property.ofValue(10_001)).build().run(runContext()));
    }
}
