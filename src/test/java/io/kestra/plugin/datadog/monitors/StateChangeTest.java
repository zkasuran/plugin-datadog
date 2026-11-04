package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.datadog.AbstractDatadogTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateChangeTest extends AbstractDatadogTest {
    private static String monitor(int id, String state, Instant modified) {
        return "{\"id\":" + id + ",\"name\":\"m" + id + "\",\"overall_state\":\"" + state + "\",\"overall_state_modified\":\"" + modified + "\"}";
    }

    private StateChange.StateChangeBuilder<?, ?> trigger() {
        return StateChange.builder()
            .id("on_alert_" + java.util.UUID.randomUUID())
            .type(StateChange.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .appKey(Property.ofValue(APP_KEY))
            .apiUrl(Property.ofValue(server.url()))
            .interval(Duration.ofMinutes(1));
    }

    private record Poll(StateChange trigger, ConditionContext conditionContext, TriggerContext triggerContext) {
        Optional<Execution> run() throws Exception {
            return trigger.evaluate(conditionContext, triggerContext);
        }

        KVStore kv() {
            return conditionContext.getRunContext().namespaceKv(triggerContext.getNamespace());
        }
    }

    private Poll poll(StateChange trigger) {
        Map.Entry<ConditionContext, io.kestra.core.models.triggers.Trigger> entry = TestsUtils.mockTrigger(runContextFactory, trigger);
        return new Poll(trigger, entry.getKey(), entry.getValue());
    }

    @Test
    void theFirstPollOnlyRecordsTheTime() throws Exception {
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", Instant.now()) + "]");
        Poll poll = poll(trigger().build());

        assertTrue(poll.run().isEmpty(), "monitors that already alert are not replayed");

        assertTrue(server.requests.isEmpty(), "the first poll does not call Datadog");
        String key = io.kestra.core.models.triggers.StatefulTriggerService.defaultKey(poll.triggerContext().getNamespace(), poll.triggerContext().getFlowId(), poll.triggerContext().getTriggerId());
        assertTrue(poll.kv().getValue(key).isPresent(), "the cursor is stored");
    }

    @Test
    void returnsOnlyTheMonitorsThatChangedAfterTheCursorAndAreInAWatchedState() throws Exception {
        Poll poll = poll(trigger().build());
        poll.run();
        Instant now = Instant.now();
        server.reply("GET", "/api/v1/monitor", 200, "[" +
            monitor(1, "Alert", now.plusSeconds(10)) + "," +      // new alert
            monitor(2, "Alert", now.minusSeconds(3600)) + "," +   // alerting since before the cursor
            monitor(3, "OK", now.plusSeconds(20)) + "," +         // changed, but not a watched state
            monitor(4, "Warn", now.plusSeconds(30)) + "," +       // warn is not watched by default
            monitor(5, "Alert", now.plusSeconds(5)) +             // second new alert, older than 1
            "]");

        Optional<Execution> execution = poll.run();

        assertTrue(execution.isPresent());
        @SuppressWarnings("unchecked")
        var monitors = (java.util.List<Map<String, Object>>) ((Map<String, Object>) execution.get().getTrigger().getVariables()).get("monitors");
        assertEquals(java.util.List.of(5, 1), monitors.stream().map(m -> ((Number) m.get("id")).intValue()).toList(), "oldest change first");
        assertEquals(2, ((Number) execution.get().getTrigger().getVariables().get("count")).intValue());
        assertEquals(API_KEY, server.requests.get(0).header("DD-API-KEY"));
        assertEquals(APP_KEY, server.requests.get(0).header("DD-APPLICATION-KEY"));
    }

    @Test
    void aMonitorIsReturnedOnce() throws Exception {
        Poll poll = poll(trigger().build());
        poll.run();
        Instant changed = Instant.now().plusSeconds(10);
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", changed) + "]");

        assertTrue(poll.run().isPresent());
        assertTrue(poll.run().isEmpty(), "the same alert is not returned again by the next poll");
    }

    @Test
    void watchedStatesCanBeChosen() throws Exception {
        Poll poll = poll(trigger().states(Property.ofValue(java.util.List.of(StateChange.State.WARN, StateChange.State.NO_DATA))).build());
        poll.run();
        Instant now = Instant.now();
        server.reply("GET", "/api/v1/monitor", 200, "[" +
            monitor(1, "Alert", now.plusSeconds(10)) + "," +
            monitor(2, "Warn", now.plusSeconds(11)) + "," +
            monitor(3, "No Data", now.plusSeconds(12)) + "]");

        Optional<Execution> execution = poll.run();

        assertTrue(execution.isPresent());
        assertEquals(2, ((Number) execution.get().getTrigger().getVariables().get("count")).intValue());
    }

    @Test
    void aRateLimitSkipsThePollAndKeepsTheCursor() throws Exception {
        assertSkippedThenRecovers(429, "{\"errors\":[\"Too many requests\"]}");
    }

    @Test
    void anOutageSkipsThePollAndKeepsTheCursor() throws Exception {
        assertSkippedThenRecovers(503, "{\"errors\":[\"down\"]}");
    }

    private void assertSkippedThenRecovers(int status, String body) throws Exception {
        Poll poll = poll(trigger().build());
        poll.run();
        Instant changed = Instant.now().plusSeconds(10);
        server.reply("GET", "/api/v1/monitor", status, body);

        assertTrue(poll.run().isEmpty(), "the poll is skipped, not failed");

        // Datadog answers again: the alert that came during the outage is returned
        server.reply("GET", "/api/v1/monitor", 200, "[" + monitor(1, "Alert", changed) + "]");
        assertTrue(poll.run().isPresent(), "the alert missed during the outage is returned once Datadog answers again");
    }

    @Test
    void anAuthenticationErrorFailsTheTrigger() throws Exception {
        Poll poll = poll(trigger().build());
        poll.run();
        server.reply("GET", "/api/v1/monitor", 403, "{\"errors\":[\"Forbidden\"]}");

        IllegalStateException error = assertThrows(IllegalStateException.class, poll::run);

        assertTrue(error.getMessage().contains("403"), error.getMessage());
        assertFalse(error.getMessage().contains(API_KEY));
    }

    @Test
    void anExecutionIsNotStartedWhenNothingChanged() throws Exception {
        Poll poll = poll(trigger().build());
        poll.run();
        server.reply("GET", "/api/v1/monitor", 200, "[]");

        assertTrue(poll.run().isEmpty());
    }

    @Test
    void parsesTheDateFormatsDatadogSends() {
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), StateChange.parseInstant("2026-01-01T10:00:00+00:00"));
        assertEquals(Instant.parse("2026-01-01T10:00:00.123456Z"), StateChange.parseInstant("2026-01-01T10:00:00.123456+00:00"));
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), StateChange.parseInstant("2026-01-01T10:00:00"));
        assertEquals(null, StateChange.parseInstant("not a date"));
        assertEquals(null, StateChange.parseInstant(null));
    }
}
