package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListTest extends AbstractDatadogTest {
    private List.ListBuilder<?, ?> task() {
        return List.builder()
            .id("monitors")
            .type(List.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()));
    }

    private static String monitors(int from, int count) {
        return IntStream.range(from, from + count)
            .mapToObj(i -> "{\"id\":" + i + ",\"name\":\"m" + i + "\",\"overall_state\":\"Alert\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void sendsTheFiltersAndReturnsTheMonitors() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, monitors(1, 2));

        List.Output output = task()
            .name(Property.ofValue("orders"))
            .tags(Property.ofValue(java.util.List.of("service:orders", "team:data")))
            .monitorTags(Property.ofValue(java.util.List.of("env:prod")))
            .groupStates(Property.ofValue(java.util.List.of("alert", "warn")))
            .build().run(runContext());

        var request = server.only();
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
        String query = URLDecoder.decode(request.query(), StandardCharsets.UTF_8);
        assertTrue(query.contains("name=orders"), query);
        assertTrue(query.contains("tags=service:orders,team:data"), query);
        assertTrue(query.contains("monitor_tags=env:prod"), query);
        assertTrue(query.contains("group_states=alert,warn"), query);
        assertTrue(query.contains("page=0"), query);

        assertEquals(2, output.getSize());
        assertEquals("m2", output.getMonitors().get(1).get("name"));
    }

    @Test
    void readsPagesUntilAShortOne() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, monitors(0, 200));
        server.reply("GET", "/api/v1/monitor", 200, monitors(200, 50));

        List.Output output = task().build().run(runContext());

        assertEquals(250, output.getSize());
        assertEquals(2, server.requests.size());
        assertTrue(server.requests.get(1).query().contains("page=1"));
    }

    @Test
    void stopsAtMaxMonitors() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, monitors(0, 200));

        List.Output output = task().maxMonitors(Property.ofValue(120)).build().run(runContext());

        assertEquals(120, output.getSize());
        assertEquals(1, server.requests.size(), "the first page already holds the limit");
        assertTrue(server.requests.get(0).query().contains("page_size=120"));
    }

    @Test
    void anEmptyListIsNotAnError() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[]");
        assertEquals(0, task().build().run(runContext()).getSize());
    }

    @Test
    void refusesAnUnexpectedAnswerAndABadLimit() {
        server.reply("GET", "/api/v1/monitor", 200, "{\"not\":\"a list\"}");
        assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().maxMonitors(Property.ofValue(0)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().maxMonitors(Property.ofValue(10_001)).build().run(runContext()));
    }

    @Test
    void theApplicationKeyIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> List.builder().id("m").type(List.class.getName())
            .apiKey(Property.ofValue(API_KEY)).apiUrl(Property.ofValue(server.url())).build().run(runContext()));
    }
}
