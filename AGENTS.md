# Kestra Datadog Plugin

## What

- Provides plugin components under `io.kestra.plugin.datadog`.
- Tasks: `events.Create`, `metrics.Submit`, `metrics.Query`, `monitors.List`, `downtimes.Create`, `downtimes.Cancel`, `logs.Send`, `logs.Search`.
- Trigger: `monitors.StateChange`.

## Why

- What user problem does this solve? Teams that report to Datadog from pipelines or read it from them, do it with ad-hoc `curl` calls and scripts that carry keys, endpoints and error handling of their own.
- Why would a team adopt this plugin in a workflow? It keeps the Datadog steps in the same Kestra flow as the rest of the pipeline, with Kestra secrets, expressions and execution history.
- What operational/business outcome does it enable? Pipelines can mark deployments on the dashboards, publish metrics, check a metric or the logs before they continue, mute monitors during maintenance and react when a monitor goes into alert.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.datadog`: `events`, `metrics`, `monitors`, `downtimes`, `logs`.

The tasks call the public Datadog API (v1 and v2) with Kestra's HTTP client. There is no Datadog SDK dependency.

### Key Plugin Classes

- `io.kestra.plugin.datadog.DatadogClient`: renders and validates the connection (`apiKey`, `appKey`, `site`, `apiUrl`), adds the `DD-API-KEY` and `DD-APPLICATION-KEY` headers, builds the regional URLs and turns an error answer into a message with the HTTP status and the `errors` Datadog returns.
- `io.kestra.plugin.datadog.AbstractDatadogTask`: base class of the tasks. It holds the connection properties.
- `io.kestra.plugin.datadog.monitors.StateChange`: polling trigger. It extends `AbstractTrigger`, not `AbstractDatadogTask` and keeps a cursor (the newest `overall_state_modified` it returned) in the namespace KV store.

### Project Structure

```
plugin-datadog/
├── src/main/java/io/kestra/plugin/datadog/
├── src/main/resources/ (metadata, doc and icons)
├── src/test/java/io/kestra/plugin/datadog/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Run `./gradlew test` and `./gradlew lintPluginDocs` before pushing; CI runs `./gradlew check`.
- A task property cannot be named `type`: it is the task type in the flow. `metrics.Submit` calls its metric type `metricType`.
- The tests use `MockDatadogServer`, which follows the Datadog OpenAPI description. No test has run against a live Datadog organization.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://docs.datadoghq.com/api/latest/
