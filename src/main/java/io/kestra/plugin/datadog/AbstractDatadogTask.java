package io.kestra.plugin.datadog;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDatadogTask extends Task {
    @Schema(
        title = "Datadog API key",
        description = "The organization API key. Use a Kestra secret, e.g. `{{ secret('DATADOG_API_KEY') }}`."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "Datadog application key",
        description = "An application key. Datadog asks for it on every call that reads data or manages monitors and downtimes, so the tasks that only send data (`events.Create`, `metrics.Submit`, `logs.Send`) work without it. " +
            "Create it with the scopes the task needs. Use a Kestra secret, e.g. `{{ secret('DATADOG_APP_KEY') }}`."
    )
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> appKey;

    @Schema(
        title = "Datadog site",
        description = "The Datadog site of the organization, the domain you see in the Datadog URL: `datadoghq.com` (US1, the default), `us3.datadoghq.com`, `us5.datadoghq.com`, `datadoghq.eu`, `ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com` or `ddog-gov.com`. " +
            "A key only works on the site of the organization that created it."
    )
    @PluginProperty(group = "connection")
    private Property<String> site;

    @Schema(
        title = "API base URL",
        description = "Replaces `https://api.<site>` when set, for a proxy, a private link or a test server. Leave it empty for the public API."
    )
    @PluginProperty(group = "advanced")
    private Property<String> apiUrl;

    /** Builds the client of this task. {@code needsAppKey} is {@code true} for the calls that Datadog does not open to an API key alone. */
    protected DatadogClient client(RunContext runContext, boolean needsAppKey) throws Exception {
        return DatadogClient.create(runContext, apiKey, appKey, site, apiUrl, needsAppKey);
    }
}
