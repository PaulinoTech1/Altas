# Failure contract and recovery

`failure_codes.json` is the shared source of truth for stable codes, categories, statuses, retry policy and corrective actions. Python loads it at startup; Rust embeds it when compiled. Codes are the machine interface. Human messages may become clearer without changing a code.

## HTTP envelope

Successful response shapes are unchanged. Every handled HTTP response includes `X-Request-ID`. Failures retain the legacy `error` string and add structured fields:

```json
{
  "error": "The native engine has no available execution slot.",
  "error_code": "ATLAS_ENGINE_BUSY",
  "failure": {
    "code": "ATLAS_ENGINE_BUSY",
    "status": 503,
    "message": "The native engine has no available execution slot.",
    "category": "capacity",
    "retryable": true,
    "action": "Wait briefly before retrying; do not start parallel retries.",
    "details": {}
  },
  "request_id": "example-server-generated-request-id"
}
```

The browser shows the code, correlation ID, HTTP status, recovery action and safe details. Collection health also exposes structured collector, metrics and investigation failures. Background jobs have no HTTP request ID; use collector kind, run timestamps and diagnostic frame locations to correlate them.

`retryable` describes whether the cause can be transient. It does **not** authorize blindly replaying a mutation. The browser performs no automatic mutation retries. Retryable HTTP failures include a `Retry-After: 3` hint. Authentication failures include `WWW-Authenticate: Bearer`; wrong-method failures include `Allow`.

## Defined outcomes

| Failure mode | Defined behavior | Recovery |
|---|---|---|
| Invalid JSON, media type, length, value or method | Request is rejected with a typed 4xx response. No collection job is launched. | Correct the indicated field or protocol. |
| Missing/invalid/revoked sensor credential | `401 ATLAS_AUTH_INVALID`; no sensor observations are accepted. | Use an enabled sensor Bearer credential. |
| Existing target or mismatched session identity | `409` conflict. No existing evidence is overwritten. | Use the existing resource or a distinct identity. |
| Collector already running | `409 ATLAS_JOB_BUSY`; no duplicate worker is launched. | Wait for completion. |
| Worker cannot start | `503 ATLAS_WORKER_START_FAILED`; running flag and lock are released. | Check resources and retry. |
| Collector fails after partial work | Completed per-target observations remain. The collector records its failure and releases its lock. | Inspect health; retry collection after correcting the cause. |
| Metrics persistence fails | Collection flags and locks still release. `runtime.metrics_failure` reports the failure separately. | Resolve storage failure; prior observations remain. |
| Investigation synchronization fails | Collection is retained. `runtime.investigation_failure` is set; snapshots/alerts may lag. | Repair the native/storage problem and refresh or rerun collection. |
| Native executable missing/unlaunchable | `503` dependency failure. No native operation starts. | Build the engine or correct permissions/libraries. |
| Native slot saturation | `503 ATLAS_ENGINE_BUSY`; the operation has not started. | Wait with backoff. |
| Native timeout/crash/invalid response | `504` or `502`. Slot is released. Mutation outcome may be unknown. | Inspect stored results before retrying. Exact capture identities deduplicate; IP sensor batches do not. |
| SQLite busy/locked | `503 ATLAS_STORAGE_BUSY`, using SQLite's primary error code. | Wait for the writer and retry with backoff. |
| SQLite full/corrupt or I/O failure | Typed storage failure; no success is reported. An uncommitted SQLite transaction rolls back. | Free storage or restore a verified backup as indicated. |
| DNS failure | Successful observation payload with `status: unknown` and `ATLAS_DNS_FAILED`; no invented port closures. | Check hostname/resolver. |
| TCP refusal/timeout/unreachable | Observation payload with the corresponding code. It is not an HTTP application outage or proof of device power state. | Check routing, filtering and selected services. |
| Metadata failure after accepted TCP | Port remains `open`; `metadata_failure` reports the failure separately. | Inspect protocol/service metadata. |
| Android Wi-Fi API unavailable | Explicit capability diagnostic; ordinary TCP collection can continue. | Check Android/Termux API availability. |
| Unexpected application bug | `500 ATLAS_INTERNAL`; public response omits raw exception text. | Retain request ID and inspect source frame metadata in server logs. |
| Invalid listener port or unavailable socket | Startup fails without claiming the app is listening. | Choose a free valid port. |

A timeout does not prove a mutation rolled back: a child may have committed before its result was lost. Operations are not automatically replayed. A process/phone crash before any response likewise leaves an unknown outcome. Query stored evidence first.

Error logs are JSON metadata with code, category, operation, exception type, frame module/function/line, and request ID. They omit raw URLs, request bodies, credential values, SQL and raw exception messages. Source file text is not logged. Capture payloads and device evidence remain accessible through their intended data APIs, not error diagnostics.

## Code catalog

HTTP status 200 below means an embedded observation/capability diagnostic, not an API failure response.

| Code | HTTP | Category | Retryable | Meaning and action |
|---|---:|---|---|---|
| `ATLAS_INPUT_INVALID` | 400 | validation | no | An input value is invalid. Correct the indicated input and submit again. |
| `ATLAS_JSON_INVALID` | 400 | validation | no | The request is not valid UTF-8 JSON. Send a JSON object encoded as UTF-8. |
| `ATLAS_LENGTH_REQUIRED` | 411 | protocol | no | The request has no Content-Length. Send a Content-Length header with the JSON request. |
| `ATLAS_BODY_TOO_LARGE` | 413 | capacity | no | The request exceeds the size limit. Split or reduce the request; inspect the documented limits. |
| `ATLAS_MEDIA_UNSUPPORTED` | 415 | protocol | no | The request content type is unsupported. Send Content-Type: application/json. |
| `ATLAS_ORIGIN_REJECTED` | 403 | authorization | no | The browser origin is not allowed. Use the dashboard served by this Atlas instance. |
| `ATLAS_NOT_FOUND` | 404 | validation | no | The requested resource does not exist. Refresh the inventory or sessions and select an existing resource. |
| `ATLAS_METHOD_NOT_ALLOWED` | 405 | protocol | no | This operation does not support that HTTP method. Use the method documented for the operation. |
| `ATLAS_CONFLICT` | 409 | conflict | no | The requested change conflicts with existing data. Use the existing resource or choose a distinct identifier. |
| `ATLAS_JOB_BUSY` | 409 | capacity | yes | This collector is already running. Wait for the current collection run to finish. |
| `ATLAS_WORKER_START_FAILED` | 503 | dependency | yes | A collection worker could not start. Check process resources and retry after other jobs finish. |
| `ATLAS_ENGINE_MISSING` | 503 | dependency | no | The native capture engine is missing. Run cargo build --release --locked in native/. |
| `ATLAS_ENGINE_BUSY` | 503 | capacity | yes | The native engine has no available execution slot. Wait briefly before retrying; do not start parallel retries. |
| `ATLAS_ENGINE_TIMEOUT` | 504 | timeout | yes | The native operation exceeded its deadline. Inspect whether the operation committed before retrying. Reuse capture identity; do not blindly resubmit sensor batches. |
| `ATLAS_ENGINE_START_FAILED` | 503 | dependency | no | The native executable could not start. Check executable permissions, architecture and runtime libraries. |
| `ATLAS_ENGINE_PROTOCOL` | 502 | dependency | no | The native engine returned an invalid response. Rebuild the native engine and restart Atlas; retain the request ID. |
| `ATLAS_ENGINE_CRASHED` | 502 | dependency | no | The native engine exited unexpectedly. Inspect server diagnostics with the request ID; rebuild before retrying. |
| `ATLAS_STORAGE_BUSY` | 503 | storage | yes | The database is busy. Wait for the current writer, then retry with backoff. |
| `ATLAS_STORAGE_FULL` | 507 | storage | no | The database cannot grow because storage is full. Free storage before retrying; do not delete the active database. |
| `ATLAS_STORAGE_CORRUPT` | 500 | storage | no | The database is corrupt or is not a SQLite database. Stop collection and restore a verified backup; retain the damaged file. |
| `ATLAS_STORAGE_FAILURE` | 500 | storage | no | A database operation failed. Check database permissions and server diagnostics before retrying. |
| `ATLAS_AUTH_INVALID` | 401 | authorization | no | The sensor credential is missing, invalid or revoked. Create an enabled sensor credential and send it as a Bearer token. |
| `ATLAS_CAPTURE_EMPTY` | 422 | validation | no | No supported scan packets were found. Provide Nordic scan lines: TIME ADDRESS RSSI HEXDATA. |
| `ATLAS_SESSION_CONFLICT` | 409 | conflict | no | The session key is already assigned to different capture content. Reuse the original content or choose a new session key. |
| `ATLAS_WIFI_UNAVAILABLE` | 503 | capability | no | The Wi-Fi IPv4 interface is unavailable. Connect the phone to Wi-Fi and check Android permissions. |
| `ATLAS_SCOPE_UNSUPPORTED` | 422 | capability | no | The Wi-Fi subnet is outside supported discovery bounds. Use a private IPv4 subnet containing at most 256 addresses. |
| `ATLAS_DNS_FAILED` | 200 | observation | yes | Name resolution did not return an address. Check the hostname and resolver; this does not prove a device is offline. |
| `ATLAS_TCP_REFUSED` | 200 | observation | no | The selected TCP connection was refused. Review selected services; a refusal is response evidence, not a device identity. |
| `ATLAS_TCP_TIMEOUT` | 200 | observation | yes | The selected TCP connection timed out. Check connectivity or filtering; do not infer that the device is powered off. |
| `ATLAS_TCP_UNREACHABLE` | 200 | observation | yes | The selected TCP connection could not be established. Check routing, permissions and the selected service. |
| `ATLAS_METADATA_FAILED` | 200 | observation | yes | The service answered but metadata collection failed. Inspect the service protocol; the successful TCP observation remains valid. |
| `ATLAS_COLLECTOR_FAILED` | 500 | collection | no | A collection run failed. Inspect collection health and server diagnostics; completed observations are retained. |
| `ATLAS_IO_FAILED` | 500 | storage | no | A required local file could not be accessed. Check file permissions and availability; consult server diagnostics. |
| `ATLAS_INTERNAL` | 500 | internal | no | An unexpected application failure occurred. Retain the request ID and inspect server diagnostics before retrying. |
| `ATLAS_RESPONSE_INVALID` | 502 | protocol | no | The server returned an invalid response. Check the localhost connection and server diagnostics. |
| `ATLAS_LIMIT_REACHED` | 422 | capacity | no | The configured resource limit has been reached. Use an existing resource or reduce the requested resource count. |
| `ATLAS_NETWORK_UNAVAILABLE` | 503 | connection | yes | The localhost server could not be reached. Check that Atlas is running, then retry once the connection is restored. |
| `ATLAS_WIFI_API_UNAVAILABLE` | 200 | capability | no | Android Wi-Fi API information is unavailable. Check Termux:API installation and Android permissions; TCP observation can continue. |
| `ATLAS_BIND_FAILED` | 503 | dependency | no | The localhost server could not bind its listening socket. Check whether another process owns the port, or choose a different --port. |

| `ATLAS_NETWORK_CHANGED` | 409 | collection | yes | Active network changed during the operation. Wait for discovery on the current network, then retry. |

## Compatibility changes

Success payloads and the legacy failure `error` string are preserved. Authentication failures now use 401, wrong-method failures use 405, missing resources use 404, conflicts use 409, unsupported/empty captures use 422, and native/storage failures use their actual 5xx statuses instead of a generic 400. External clients must branch on codes/status, not exact human wording.

The catalog must ship with the Python package and be rebuilt into Rust when changed. A missing or malformed catalog is a bootstrap configuration error that prevents startup. CLI argument syntax failures still use argparse's normal exit code 2; native structured failures exit 1.

## Network changes

`ATLAS_NETWORK_CHANGED` (HTTP 409, collection, retryable) rejects a write whose captured network context is no longer active. Wait for discovery on the current network, then retry. In-flight collectors discard stale results; previous contexts retain their existing history. Network switches are detected from visible address/subnet information, not inferred SSID names.
