package io.kestra.plugin.datadog;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal Datadog HTTP client shared by every task and trigger of the plugin.
 * <p>
 * It adds the {@code DD-API-KEY} and {@code DD-APPLICATION-KEY} headers, builds the regional base URL and turns an
 * error answer into a message that names the HTTP status and the {@code errors} returned by Datadog. The keys are
 * never written to a log or to an error message.
 */
public final class DatadogClient {
    public static final String DEFAULT_SITE = "datadoghq.com";

    // a DNS name only, so that a rendered `site` cannot add a path, a port or credentials to the URL
    private static final Pattern SITE = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");

    private final RunContext runContext;
    private final String apiKey;
    private final String appKey;
    private final String site;
    private final String apiUrl;

    private DatadogClient(RunContext runContext, String apiKey, String appKey, String site, String apiUrl) {
        this.runContext = runContext;
        this.apiKey = apiKey;
        this.appKey = appKey;
        this.site = site;
        this.apiUrl = apiUrl;
    }

    /**
     * Renders the connection properties and validates them.
     *
     * @param needsAppKey {@code true} for the endpoints that read data or manage monitors, which Datadog only opens to an
     *                    application key. The endpoints that only send data need the API key alone.
     */
    public static DatadogClient create(
        RunContext runContext,
        Property<String> apiKey,
        Property<String> appKey,
        Property<String> site,
        Property<String> apiUrl,
        boolean needsAppKey
    ) throws Exception {
        String renderedApiKey = runContext.render(apiKey).as(String.class).orElse("").trim();
        if (renderedApiKey.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `apiKey` must not be blank");
        }

        String renderedAppKey = runContext.render(appKey).as(String.class).orElse("").trim();
        if (needsAppKey && renderedAppKey.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `appKey` must not be blank: this endpoint needs an application key in addition to the API key");
        }

        String renderedSite = runContext.render(site).as(String.class).orElse("").trim();
        if (renderedSite.isEmpty()) {
            renderedSite = DEFAULT_SITE;
        }
        if (!SITE.matcher(renderedSite).matches()) {
            throw new IllegalArgumentException("The Datadog `site` must be a domain name such as `datadoghq.com` or `datadoghq.eu` but was '" + renderedSite + "'");
        }

        String renderedApiUrl = runContext.render(apiUrl).as(String.class).orElse("").trim();
        if (!renderedApiUrl.isEmpty()) {
            URI uri = URI.create(renderedApiUrl);
            if (uri.getScheme() == null || !(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getHost() == null) {
                throw new IllegalArgumentException("The Datadog `apiUrl` must be an http or https URL but was '" + renderedApiUrl + "'");
            }
        }

        return new DatadogClient(runContext, renderedApiKey, renderedAppKey, renderedSite, renderedApiUrl);
    }

    /** The base URL of the Datadog API: the override if given, otherwise {@code https://api.<site>}. */
    public String apiBase() {
        return trimSlash(apiUrl.isEmpty() ? "https://api." + site : apiUrl);
    }

    /** The base URL of the log intake: the override if given, otherwise {@code https://http-intake.logs.<site>}. */
    public String logsIntakeBase(String intakeUrl) {
        String override = intakeUrl == null ? "" : intakeUrl.trim();
        return trimSlash(override.isEmpty() ? "https://http-intake.logs." + site : override);
    }

    public Response get(String path, Map<String, ?> query) throws Exception {
        return exchange("GET", apiBase(), path, query, null, true);
    }

    public Response post(String path, Object body) throws Exception {
        return exchange("POST", apiBase(), path, Map.of(), body, true);
    }

    public Response delete(String path) throws Exception {
        return exchange("DELETE", apiBase(), path, Map.of(), null, true);
    }

    /**
     * Sends a request and returns the answer whatever its status, so that a caller such as a trigger can decide what to do
     * with a rate limit or a server error. A network failure still throws.
     */
    public Response exchange(String method, String base, String path, Map<String, ?> query, Object body, boolean failOnError) throws Exception {
        URI uri = URI.create(trimSlash(base) + path + queryString(query));

        HttpRequest.HttpRequestBuilder request = HttpRequest.builder()
            .uri(uri)
            .method(method)
            .addHeader("DD-API-KEY", apiKey)
            .addHeader("Accept", "application/json");
        if (!appKey.isEmpty()) {
            request.addHeader("DD-APPLICATION-KEY", appKey);
        }
        if (body != null) {
            request.body(HttpRequest.JsonRequestBody.of(body));
        }

        HttpResponse<String> response;
        // allowFailed: the status is handled here, with the message Datadog sends
        try (HttpClient client = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder().allowFailed(Property.ofValue(true)).build())
            .build()) {
            response = client.request(request.build(), String.class);
        } catch (HttpClientException | IOException e) {
            throw new IllegalStateException("Datadog could not be reached at " + uri.getScheme() + "://" + uri.getAuthority() + ": " + e.getMessage(), e);
        }

        int status = response.getStatus().getCode();
        String raw = response.getBody() == null ? "" : response.getBody();
        String retryAfter = response.getHeaders().firstValue("X-RateLimit-Reset").orElse(null);

        if (failOnError && (status < 200 || status >= 300)) {
            throw new DatadogException(status, describe(method, uri, status, raw, retryAfter));
        }

        return new Response(status, raw, retryAfter);
    }

    private static String describe(String method, URI uri, int status, String raw, String retryAfter) {
        StringBuilder message = new StringBuilder("Datadog answered ").append(method).append(' ').append(uri.getPath())
            .append(" with HTTP ").append(status);
        String detail = errors(raw);
        if (!detail.isEmpty()) {
            message.append(": ").append(detail);
        }
        switch (status) {
            case 401, 403 -> message.append(". Check the `apiKey` and the `appKey`, that they belong to the `site` '")
                .append(uri.getHost()).append("' and that the application key has the permission this call needs");
            case 404 -> message.append(". Check the `site` and the id in the request");
            case 429 -> message.append(". The rate limit is reached").append(retryAfter == null ? "" : ", it resets in " + retryAfter + " seconds");
            default -> {
            }
        }
        return message.toString();
    }

    /** Extracts the {@code errors} list of a Datadog error answer or the start of the body when the shape is different. */
    static String errors(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            Object json = JacksonMapper.ofJson().readValue(raw, Object.class);
            if (json instanceof Map<?, ?> map && map.get("errors") instanceof List<?> list && !list.isEmpty()) {
                return String.join("; ", list.stream().map(String::valueOf).toList());
            }
        } catch (JsonProcessingException ignored) {
            // not JSON: show the start of it
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
    }

    static String queryString(Map<String, ?> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            out.append(out.isEmpty() ? '?' : '&')
                .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static String trimSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    /** Builds the query map in a stable order. */
    public static Map<String, Object> query() {
        return new LinkedHashMap<>();
    }

    /** An answer of Datadog: the status, the raw body and, when sent, the number of seconds before the rate limit resets. */
    public record Response(int status, String body, String rateLimitReset) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The body parsed as JSON or {@code null} when it is empty. */
        public Object json() {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                return JacksonMapper.ofJson().readValue(body, Object.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Datadog returned an answer that is not valid JSON: " + e.getOriginalMessage(), e);
            }
        }

        /** The body as a JSON object. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> map() {
            Object json = json();
            if (json == null) {
                return Map.of();
            }
            if (json instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalStateException("Datadog returned a " + json.getClass().getSimpleName() + " where a JSON object was expected");
        }
    }

    /** Raised when Datadog answers with an error status. */
    public static class DatadogException extends IllegalStateException {
        private final int status;

        public DatadogException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
