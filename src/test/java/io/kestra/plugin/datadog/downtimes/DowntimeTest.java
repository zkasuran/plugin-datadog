package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DowntimeTest extends AbstractDatadogTest {
    private Create.CreateBuilder<?, ?> create() {
        server.reply("POST", "/api/v2/downtime", 200, "{\"data\":{\"id\":\"dt-1\",\"type\":\"downtime\",\"attributes\":{\"status\":\"active\"}}}");
        return Create.builder()
            .id("mute")
            .type(Create.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()))
            .scope(Property.ofValue("env:prod AND service:orders"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes() throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) server.only().json().get("data")).get("attributes");
    }

    @Test
    void createsADowntimeOnMonitorTagsForAGivenDuration() throws Exception {
        Instant before = Instant.now();
        Create.Output output = create()
            .monitorTags(Property.ofValue(List.of("service:orders")))
            .duration(Property.ofValue(Duration.ofMinutes(30)))
            .message(Property.ofValue("deploy"))
            .displayTimezone(Property.ofValue("Europe/Paris"))
            .muteFirstRecoveryNotification(Property.ofValue(true))
            .build().run(runContext());

        var request = server.only();
        assertEquals("/api/v2/downtime", request.path());
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
        assertEquals("downtime", ((Map<?, ?>) request.json().get("data")).get("type"));

        Map<String, Object> attributes = attributes();
        assertEquals("env:prod AND service:orders", attributes.get("scope"));
        assertEquals(Map.of("monitor_tags", List.of("service:orders")), attributes.get("monitor_identifier"));
        assertEquals("deploy", attributes.get("message"));
        assertEquals("Europe/Paris", attributes.get("display_timezone"));
        assertEquals(true, attributes.get("mute_first_recovery_notification"));
        @SuppressWarnings("unchecked")
        Map<String, Object> schedule = (Map<String, Object>) attributes.get("schedule");
        assertFalse(schedule.containsKey("start"), "it starts now when no start is given");
        Instant end = Instant.parse((String) schedule.get("end"));
        assertTrue(end.isAfter(before.plus(Duration.ofMinutes(29))) && end.isBefore(Instant.now().plus(Duration.ofMinutes(31))));

        assertEquals("dt-1", output.getDowntimeId());
        assertEquals("active", output.getStatus());
        assertEquals(end, output.getEnd());
    }

    @Test
    void createsADowntimeOnOneMonitorWithStartAndEnd() throws Exception {
        create().monitorId(Property.ofValue(42L))
            .start(Property.ofValue(Instant.parse("2026-01-01T10:00:00Z")))
            .end(Property.ofValue(Instant.parse("2026-01-01T11:00:00Z")))
            .build().run(runContext());

        Map<String, Object> attributes = attributes();
        assertEquals(Map.of("monitor_id", 42), attributes.get("monitor_identifier"));
        assertEquals(Map.of("start", "2026-01-01T10:00:00Z", "end", "2026-01-01T11:00:00Z"), attributes.get("schedule"));
    }

    @Test
    void aDurationRunsFromTheStart() throws Exception {
        Create.Output output = create().monitorId(Property.ofValue(1L))
            .start(Property.ofValue(Instant.parse("2026-01-01T10:00:00Z")))
            .duration(Property.ofValue(Duration.ofHours(2)))
            .build().run(runContext());

        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), output.getEnd());
    }

    @Test
    void refusesADowntimeThatCouldNeverEndOrIsAmbiguous() {
        var end = Property.ofValue(Instant.now().plusSeconds(60));
        // no end and no duration
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).build().run(runContext()));
        // both
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).end(end).duration(Property.ofValue(Duration.ofMinutes(1))).build().run(runContext()));
        // no monitor, or both monitor selectors
        assertThrows(IllegalArgumentException.class, () -> create().end(end).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).monitorTags(Property.ofValue(List.of("a:b"))).end(end).build().run(runContext()));
        // an end before the start, a zero duration, a blank scope
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).start(Property.ofValue(Instant.now().plusSeconds(120))).end(end).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).duration(Property.ofValue(Duration.ZERO)).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> create().monitorId(Property.ofValue(1L)).duration(Property.ofValue(Duration.ofMinutes(1))).scope(Property.ofValue(" ")).build().run(runContext()));
        assertTrue(server.requests.isEmpty(), "nothing is sent for an invalid downtime");
    }

    @Test
    void failsWhenDatadogReturnsNoIdSoThatNoDowntimeIsLeftBehindUnnoticed() {
        server.reply("POST", "/api/v2/downtime", 200, "{\"data\":{}}");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Create.builder().id("m").type(Create.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .scope(Property.ofValue("*")).monitorId(Property.ofValue(1L)).duration(Property.ofValue(Duration.ofMinutes(5)))
            .build().run(runContext()));
        assertTrue(error.getMessage().contains("no id"), error.getMessage());
    }

    @Test
    void cancelsADowntime() throws Exception {
        server.reply("DELETE", "/api/v2/downtime/dt-1", 204, "");

        Cancel.Output output = Cancel.builder().id("unmute").type(Cancel.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .downtimeId(Property.ofValue("dt-1")).build().run(runContext());

        var request = server.only();
        assertEquals("DELETE", request.method());
        assertEquals("/api/v2/downtime/dt-1", request.path());
        assertEquals(APP_KEY, request.header("DD-APPLICATION-KEY"));
        assertTrue(output.getCancelled());
        assertEquals("dt-1", output.getDowntimeId());
    }

    @Test
    void aMissingDowntimeFailsUnlessIgnored() throws Exception {
        server.reply("DELETE", "/api/v2/downtime/gone", 404, "{\"errors\":[\"Downtime not found\"]}");
        var strict = Cancel.builder().id("unmute").type(Cancel.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .downtimeId(Property.ofValue("gone")).build();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> strict.run(runContext()));
        assertTrue(error.getMessage().contains("404"), error.getMessage());

        Cancel.Output output = Cancel.builder().id("unmute").type(Cancel.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .downtimeId(Property.ofValue("gone")).ignoreNotFound(Property.ofValue(true)).build().run(runContext());
        assertFalse(output.getCancelled());
    }

    @Test
    void otherErrorsAreNotIgnored() {
        server.reply("DELETE", "/api/v2/downtime/x", 403, "{\"errors\":[\"Forbidden\"]}");
        assertThrows(IllegalStateException.class, () -> Cancel.builder().id("unmute").type(Cancel.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .downtimeId(Property.ofValue("x")).ignoreNotFound(Property.ofValue(true)).build().run(runContext()));
    }

    @Test
    void theIdCannotChangeThePath() throws Exception {
        server.reply("DELETE", "/api/v2/downtime/a%2Fb%3Fx", 204, "");
        Cancel.builder().id("unmute").type(Cancel.class.getName())
            .apiKey(Property.ofValue(API_KEY)).appKey(Property.ofValue(APP_KEY)).apiUrl(Property.ofValue(server.url()))
            .downtimeId(Property.ofValue("a/b?x")).build().run(runContext());
        assertEquals(1, server.requests.size());
        assertNull(server.requests.get(0).query(), "the id must stay in the path");
    }
}
