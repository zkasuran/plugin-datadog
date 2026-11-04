package io.kestra.plugin.datadog.downtimes;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.datadog.AbstractDatadogTask;
import io.kestra.plugin.datadog.DatadogClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Cancel a Datadog downtime",
    description = "Cancels a downtime so that the monitors notify again. Needs the API key and an application key with the `monitors_downtime` scope. " +
        "Datadog answers 404 for a downtime that is already cancelled or does not exist: that fails the task, unless `ignoreNotFound` is true."
)
@Plugin(
    examples = {
        @Example(
            title = "Cancel the downtime created earlier in the flow",
            full = true,
            code = """
                id: datadog_cancel_downtime
                namespace: company.observability

                tasks:
                  - id: unmute
                    type: io.kestra.plugin.datadog.downtimes.Cancel
                    apiKey: "{{ secret('DATADOG_API_KEY') }}"
                    appKey: "{{ secret('DATADOG_APP_KEY') }}"
                    downtimeId: "{{ outputs.mute.downtimeId }}"
                    ignoreNotFound: true
                """
        )
    }
)
public class Cancel extends AbstractDatadogTask implements RunnableTask<Cancel.Output> {
    @Schema(title = "Downtime id", description = "The id of the downtime, as returned by `downtimes.Create`.")
    @NotNull
    private Property<String> downtimeId;

    @Builder.Default
    @Schema(title = "Ignore a missing downtime", description = "Succeeds when Datadog does not find the downtime, for example because it ended and was removed.", defaultValue = "false")
    private Property<Boolean> ignoreNotFound = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String id = runContext.render(this.downtimeId).as(String.class).orElse("").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("The Datadog `downtimeId` must not be blank");
        }

        boolean ignore = runContext.render(this.ignoreNotFound).as(Boolean.class).orElse(false);
        try {
            client(runContext, true).delete("/api/v2/downtime/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        } catch (DatadogClient.DatadogException e) {
            if (e.getStatus() == 404 && ignore) {
                runContext.logger().info("Datadog downtime {} was not found, nothing to cancel", id);
                return Output.builder().downtimeId(id).cancelled(false).build();
            }
            throw e;
        }

        runContext.logger().info("Datadog downtime {} cancelled", id);
        return Output.builder().downtimeId(id).cancelled(true).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Downtime id")
        private final String downtimeId;

        @Schema(title = "Cancelled", description = "`true` when Datadog cancelled the downtime, `false` when it was not found and `ignoreNotFound` is set.")
        private final Boolean cancelled;
    }
}
