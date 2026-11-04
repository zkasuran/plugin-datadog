package io.kestra.plugin.datadog.events;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.property.Property;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import io.kestra.plugin.datadog.MockDatadogServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTest extends AbstractDatadogTest {
    private Create.CreateBuilder<?, ?> task() {
        return Create.builder()
            .id("event")
            .type(Create.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .apiUrl(Property.ofValue(server.url()))
            .title(Property.ofValue("Orders finished"))
            .text(Property.ofValue("Loaded 1250 rows"));
    }

    @Test
    void postsTheEventWithTheApiKeyOnly() throws Exception {
        server.reply("POST", "/api/v1/events", 202, "{\"status\":\"ok\",\"event\":{\"id\":123,\"id_str\":\"123\",\"url\":\"https://app.datadoghq.com/event/event?id=123\"}}");

        Create.Output output = task()
            .alertType(Property.ofValue(Create.AlertType.SUCCESS))
            .priority(Property.ofValue(Create.Priority.LOW))
            .tags(Property.ofValue(List.of("env:prod", "source:kestra")))
            .host(Property.ofValue("worker-1"))
            .aggregationKey(Property.ofValue("orders"))
            .sourceTypeName(Property.ofValue("kestra"))
            .dateHappened(Property.ofValue(Instant.parse("2026-01-01T00:00:00Z")))
            .build().run(runContext());

        MockDatadogServer.Received request = server.only();
        assertEquals("POST", request.method());
        assertEquals("/api/v1/events", request.path());
        assertEquals(API_KEY, request.header("DD-API-KEY"));
        assertNull(request.header("DD-APPLICATION-KEY"), "an event needs no application key");

        Map<String, Object> body = request.json();
        assertEquals("Orders finished", body.get("title"));
        assertEquals("Loaded 1250 rows", body.get("text"));
        assertEquals("success", body.get("alert_type"));
        assertEquals("low", body.get("priority"));
        assertEquals(List.of("env:prod", "source:kestra"), body.get("tags"));
        assertEquals("worker-1", body.get("host"));
        assertEquals("orders", body.get("aggregation_key"));
        assertEquals("kestra", body.get("source_type_name"));
        assertEquals(1767225600, ((Number) body.get("date_happened")).longValue());

        assertEquals("123", output.getEventId());
        assertEquals("https://app.datadoghq.com/event/event?id=123", output.getUrl());
        assertEquals("ok", output.getStatus());
    }

    @Test
    void sendsOnlyTitleAndTextWhenNothingElseIsSet() throws Exception {
        server.reply("POST", "/api/v1/events", 202, "{\"status\":\"ok\"}");

        Create.Output output = task().build().run(runContext());

        Map<String, Object> body = server.only().json();
        assertEquals(Map.of("title", "Orders finished", "text", "Loaded 1250 rows"), body);
        assertNull(output.getEventId());
    }

    @Test
    void rendersTemplatedFields() throws Exception {
        Property<String> title = JacksonMapper.ofJson().readValue("\"Run {{ execution.id }}\"", new TypeReference<Property<String>>() {
        });

        task().title(title).build().run(runContextFactory.of(Map.of("execution", Map.of("id", "exec123"))));

        assertEquals("Run exec123", server.only().json().get("title"));
    }

    @Test
    void refusesATitleOrTextThatDatadogWouldReject() {
        assertThrows(IllegalArgumentException.class, () -> task().title(Property.ofValue("  ")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().title(Property.ofValue("t".repeat(501))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().text(Property.ofValue("")).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().text(Property.ofValue("x".repeat(4001))).build().run(runContext()));
        assertThrows(IllegalArgumentException.class, () -> task().aggregationKey(Property.ofValue("k".repeat(101))).build().run(runContext()));
        assertTrue(server.requests.isEmpty(), "nothing is sent for an invalid event");
    }

    @Test
    void anErrorAnswerFailsTheTaskWithoutShowingTheKey() {
        server.reply("POST", "/api/v1/events", 403, "{\"errors\":[\"Forbidden\"]}");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));

        assertTrue(error.getMessage().contains("HTTP 403"), error.getMessage());
        assertTrue(error.getMessage().contains("Forbidden"), error.getMessage());
        assertFalse(error.getMessage().contains(API_KEY), "the API key must not appear in an error");
    }

    @Test
    void aRateLimitIsReported() {
        server.reply("POST", "/api/v1/events", 429, "{\"errors\":[\"Too many requests\"]}");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> task().build().run(runContext()));

        assertTrue(error.getMessage().contains("rate limit"), error.getMessage());
    }

    @Test
    void siteSelectsTheRegionalHost() throws Exception {
        // a task with a site and no apiUrl must call the regional API: only the URL it builds is checked, so use the client
        var client = io.kestra.plugin.datadog.DatadogClient.create(runContext(), Property.ofValue("k"), null, Property.ofValue("datadoghq.eu"), null, false);
        assertEquals("https://api.datadoghq.eu", client.apiBase());
    }
}
