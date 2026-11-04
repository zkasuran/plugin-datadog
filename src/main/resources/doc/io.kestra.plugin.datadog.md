# How to use the Datadog plugin

This plugin provides Kestra tasks to send events, custom metrics and logs to Datadog, to query metrics and logs, to list monitors and to mute them with a downtime and a polling trigger that starts a flow when monitors go into alert. Each task and the trigger have their own documentation page with full examples.

## Authentication

Datadog uses two keys and what a call needs depends on whether it sends or reads data.

- `apiKey` identifies the organization. Every task needs it.
- `appKey` is an application key. Datadog asks for it on the calls that read data or manage monitors and downtimes. Create it with only the scopes the flow needs.

| Component | Keys | Scope of the application key |
|---|---|---|
| `events.Create`, `metrics.Submit`, `logs.Send` | `apiKey` | none |
| `metrics.Query` | `apiKey`, `appKey` | `timeseries_query` |
| `monitors.List`, `monitors.StateChange` | `apiKey`, `appKey` | `monitors_read` |
| `downtimes.Create`, `downtimes.Cancel` | `apiKey`, `appKey` | `monitors_downtime` |
| `logs.Search` | `apiKey`, `appKey` | `logs_read_data` |

Store both as Kestra secrets: `apiKey: "{{ secret('DATADOG_API_KEY') }}"` and `appKey: "{{ secret('DATADOG_APP_KEY') }}"`. The keys are sent as the `DD-API-KEY` and `DD-APPLICATION-KEY` headers and are never written to a log or to an error message.

## Site

A key only works on the Datadog site of the organization that created it. Set `site` to the domain you see in the Datadog URL: `datadoghq.com` (the default), `us3.datadoghq.com`, `us5.datadoghq.com`, `datadoghq.eu`, `ap1.datadoghq.com`, `ap2.datadoghq.com`, `uk1.datadoghq.com` or `ddog-gov.com`. The tasks call `https://api.<site>` and `logs.Send` calls `https://http-intake.logs.<site>`. `apiUrl` (and `intakeUrl` for `logs.Send`) replace those addresses, for a proxy, a private link or a test server.

## Tasks

### events.Create

Posts an event to the event stream, for example to mark a deployment or a pipeline run on the dashboards. `title` and `text` are required. Datadog answers `202` as soon as it has accepted the event, so it can take a moment to show in the stream.

### metrics.Submit

Sends custom metric points. Give `metric` and `value` for one point or `series` for several series in the shape of the Datadog `POST /api/v2/series` body. A point without a timestamp gets the time the task runs. Datadog accepts points up to one hour in the past and one hour in the future and processes them after it has answered, so a rejected point does not fail the task.

### metrics.Query

Runs a metrics query over a window and returns the series, with `seriesCount` and `pointCount` so that a flow can tell that a query matched nothing. A query Datadog cannot run is answered with `200` and `status: error` and the task fails with the message Datadog gives.

### monitors.List

Lists monitors by name, tags, monitor tags or group state, page by page up to `maxMonitors` (1000 by default). `groupStates` is applied by Datadog and selects monitors that have a group in that state, which is not always the monitor's `overall_state`.

### downtimes.Create and downtimes.Cancel

`Create` mutes one monitor (`monitorId`) or the monitors with given `monitorTags`, for the groups that match `scope`. A downtime needs an end (`end` or `duration` from `start`), because a downtime without one mutes the monitors until someone cancels it and a failed flow may never do that. `Cancel` removes it. A downtime that Datadog does not find fails `Cancel` unless `ignoreNotFound` is true.

### logs.Send and logs.Search

`Send` posts one entry (`message`) or up to 1000 (`logs`) to the intake. `Search` runs a Log Explorer query over a window and follows the cursor until `maxResults` events are collected. Datadog limits this endpoint to 300 requests per hour.

## Triggers

### monitors.StateChange

Polls the monitors and starts one execution with the monitors that entered one of the watched `states` (`ALERT` by default) since the previous poll. The execution receives `{{ trigger.monitors }}` and `{{ trigger.count }}`.

A monitor is returned when its `overall_state` is watched and its `overall_state_modified` is later than the previous poll. The trigger keeps the newest `overall_state_modified` it has returned in the namespace KV store, so a monitor that stays in alert starts one execution, not one per poll. The first poll only records the starting time. The cursor moves once the execution has been created, so if creating it fails the same monitors are returned by the next poll.

Limitations:

- A monitor that goes into alert and recovers between two polls is not seen. Keep `interval` shorter than the shortest outage that matters.
- At most `maxMonitors` monitors are read per poll, in the order Datadog returns them. When the organization has more, narrow the poll with `name`, `tags` or `monitorTags`.
- A rate limit (HTTP 429) or a server error (5xx) skips that poll and the next one reads the same window again. An authentication or permission error fails the trigger.
- The automated tests use a local mock of the Datadog API that follows the published OpenAPI description. They do not cover the behavior of a live Datadog organization.
