package io.kestra.plugin.datadog;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
class DatadogClientTest {
    @Inject
    private RunContextFactory runContextFactory;

    private DatadogClient client(String site, String apiUrl, String appKey, boolean needsAppKey) throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());
        return DatadogClient.create(runContext, Property.ofValue("api"), appKey == null ? null : Property.ofValue(appKey),
            site == null ? null : Property.ofValue(site), apiUrl == null ? null : Property.ofValue(apiUrl), needsAppKey);
    }

    @Test
    void baseUrlFollowsTheSite() throws Exception {
        assertEquals("https://api.datadoghq.com", client(null, null, null, false).apiBase());
        assertEquals("https://api.datadoghq.eu", client("datadoghq.eu", null, null, false).apiBase());
        assertEquals("https://api.us3.datadoghq.com", client("us3.datadoghq.com", null, null, false).apiBase());
        assertEquals("https://http-intake.logs.datadoghq.eu", client("datadoghq.eu", null, null, false).logsIntakeBase(null));
        assertEquals("http://localhost:1/x", client(null, "http://localhost:1/x/", null, false).apiBase());
    }

    @Test
    void siteCannotAddAPathAPortOrCredentials() {
        for (String bad : new String[]{"evil.com/path", "evil.com:8080", "user@evil.com", "a b", "evil.com?x=1", "-bad.com"}) {
            assertThrows(IllegalArgumentException.class, () -> client(bad, null, null, false), bad);
        }
    }

    @Test
    void apiUrlMustBeHttp() {
        assertThrows(IllegalArgumentException.class, () -> client(null, "ftp://example.com", null, false));
        assertThrows(IllegalArgumentException.class, () -> client(null, "not a url", null, false));
    }

    @Test
    void appKeyIsRequiredOnlyWhenTheCallNeedsIt() throws Exception {
        client(null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> client(null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> client(null, null, "   ", true));
        client(null, null, "app", true);
    }

    @Test
    void blankApiKeyIsRefused() {
        RunContext runContext = runContextFactory.of(Map.of());
        assertThrows(IllegalArgumentException.class, () -> DatadogClient.create(runContext, Property.ofValue("  "), null, null, null, false));
    }

    @Test
    void errorsOfDatadogAreShown() {
        assertEquals("Forbidden; other", DatadogClient.errors("{\"errors\":[\"Forbidden\",\"other\"]}"));
        assertEquals("plain text", DatadogClient.errors("plain text"));
        assertEquals("", DatadogClient.errors(""));
        assertTrue(DatadogClient.errors("x".repeat(500)).endsWith("..."));
    }

    @Test
    void queryStringIsEncodedAndSkipsNulls() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("query", "avg:system.cpu{env:prod} by {host}");
        params.put("skip", null);
        params.put("page", 2);
        String query = DatadogClient.queryString(params);
        assertEquals("?query=avg%3Asystem.cpu%7Benv%3Aprod%7D+by+%7Bhost%7D&page=2", query);
        assertFalse(query.contains("skip"));
        assertEquals("", DatadogClient.queryString(Map.of()));
    }
}
