<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Datadog Plugin

## Why

- What user problem does this solve? Teams that report to Datadog from pipelines or read it from them, do it with ad-hoc `curl` calls and scripts that carry keys, endpoints and error handling of their own.
- Why would a team adopt this plugin in a workflow? It keeps the Datadog steps in the same Kestra flow as the rest of the pipeline, with Kestra secrets, expressions and execution history and with errors that name the HTTP status and what Datadog said.
- What operational/business outcome does it enable? Pipelines can mark deployments and runs on the dashboards, publish business and pipeline metrics, check a metric or the logs before they continue, mute monitors during maintenance and react when a monitor goes into alert.

## What

- Provides plugin components under `io.kestra.plugin.datadog`.
- Tasks:
  - `events.Create` posts an event to the event stream.
  - `metrics.Submit` sends custom gauge, count and rate points.
  - `metrics.Query` queries timeseries.
  - `monitors.List` lists monitors.
  - `downtimes.Create` and `downtimes.Cancel` mute monitors for a limited time and unmute them.
  - `logs.Send` sends log entries.
  - `logs.Search` searches the logs.
- Trigger:
  - `monitors.StateChange` polls the monitors and starts a flow when monitors go into alert.
- The tasks use Kestra's HTTP client against the public Datadog API (v1 and v2), so the plugin has no dependency besides Kestra.

## Authentication

Provide the keys through Kestra secrets, for example `{{ secret('DATADOG_API_KEY') }}`.

- `apiKey` is needed by every task.
- `appKey` is needed by the calls that read data or manage monitors and downtimes: `metrics.Query`, `monitors.List`, `monitors.StateChange`, `downtimes.Create`, `downtimes.Cancel` and `logs.Search`.
- `site` selects the Datadog site of the organization (`datadoghq.com` by default, `datadoghq.eu`, `us3.datadoghq.com` and so on). A key only works on the site that created it.

See [the plugin documentation](src/main/resources/doc/io.kestra.plugin.datadog.md) for the scopes each call needs and for the behavior of each task and of the trigger.

## Example

```yaml
id: datadog_deploy_window
namespace: company.observability

tasks:
  - id: mute
    type: io.kestra.plugin.datadog.downtimes.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    scope: "env:prod AND service:orders"
    monitorTags: ["service:orders"]
    duration: PT30M

  - id: deploy
    type: io.kestra.plugin.core.log.Log
    message: Deploying the orders service

  - id: mark
    type: io.kestra.plugin.datadog.events.Create
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    title: "Orders deployed"
    text: "Execution {{ execution.id }}"
    alertType: SUCCESS
    tags: ["env:prod", "source:kestra"]

  - id: unmute
    type: io.kestra.plugin.datadog.downtimes.Cancel
    apiKey: "{{ secret('DATADOG_API_KEY') }}"
    appKey: "{{ secret('DATADOG_APP_KEY') }}"
    downtimeId: "{{ outputs.mute.downtimeId }}"
```

## Tests

`./gradlew test` runs the unit tests against a local mock of the Datadog API. They have not been run against a live Datadog organization.

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.
