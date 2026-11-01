# Rule runner (M2.1)

Node **22.13+ / 22.x**, ESM, AJV **8.20.0** with
`allErrors:false, strict:true, validateFormats:false`, matching the frozen legacy
runtime. No framework, build step or Java integration is required.

```sh
cd rule-runner
npm ci --ignore-scripts
npm test
QF_RULE_RUNNER_TOKEN=local-secret QF_BLOB_DIR=/data/blobs npm start
```

The service listens on port 8090 (`PORT` overrides it). Both environment variables
are mandatory; startup fails closed without a token. All four endpoints require
`X-Internal-Token`, including health and metrics. Compose injects the same token
into app and runner; its default is for local development. Set a private token
in deployment configuration before production use. Do not publish this port.

## Protocol

See [protocol v1](../docs/design/03-rule-runner-protocol.md) for request bodies and
all ten operations. Success is `{ok:true,data,elapsedMs}`; rejected rules,
invalid execution envelopes and size/hash failures use HTTP 200 with
`{ok:false,code,message}`. Messages never contain extension exceptions, paths or
stacks. Authentication failures use 401, unknown HTTP endpoints 404,
overload/shutdown 503, internal host faults 500.

`deadlineMs` defaults to 8000 and includes body/file loading and queue waiting.
Accepted values are integer milliseconds from 1 through 2147483647. HTTP body
transport also has a 15-second request timeout. Every hook, including JSON
serialization and promise microtasks inside the VM, has a 650ms limit.
Limits are byte counts: input 8 MiB, each rules/schema asset 1 MiB, output 2 MiB.

`priority` is an optional envelope field (`foreground` or `background`). Without
it, capabilities and the four batch operations use the background queue; other
operations use the foreground queue. FIFO is preserved within each priority.
Foreground jobs run before queued background jobs; running work is not preempted.
Queue expiry returns `RULE_TIMEOUT` without running the job. The default queue
has 200 waiting slots, with separate admission bounding for body/file loading.

`submit` with `input.withScore:true` performs the ordinary legacy submit, then
executes the equivalent single-question scoreBatch in a **new** VM. Draft outline
scoring also gets a new VM, preserving its legacy isolation. Legacy
`projectAfterValidation` is supported as an optional validateAnswer input.
Development extensions, missing schemas and legacy host-only envelope switches
are outside this service's published protocol.

## Files and isolation

`QF_BLOB_DIR` is the blobs directory, not its parent. For a hash `abcd…`, the
only permitted asset layout is `sha256/ab/cd/abcd…`. Each request checks regular
files, rejects symlinks, enforces size and recomputes SHA-256. Workers repeat
asset checks before use, even when reused. Assets must be immutable, as in
[ADR-007](../docs/adr/ADR-007-file-storage.md); the runner mounts them read-only.

The HTTP process never loads AJV or evaluates extension code. Workers are grouped
by package digest, asset hashes and API declarations, with at most two per
package and a hard global limit (`QF_RULE_WORKER_LIMIT`, default CPU count × 2).
Each child has an empty environment and uses `--permission`,
`--max-old-space-size=96`, and `--disable-proto=throw`. Its read grants cover only
the trusted service source, installed dependencies and package.json needed to
boot Node, plus the three exact package asset files. It receives no blob-tree,
write, subprocess, worker-thread, inspector or addon permission grants.

Requests create fresh null-prototype VM globals with string/Wasm code generation
disabled. Only JSON crosses the realm boundary. Intrinsic constructors and
prototypes are frozen to reject prototype modification. The pool retires workers
on hook/request timeout, crash, output overflow, 500 served requests, or 60 seconds
idle. Compose additionally runs as non-root, drops capabilities, disallows new
privileges, bounds memory/PIDs and disables core dumps. Only app and runner join
the dedicated `internal:true` network in the base Compose. The loadtest overlay also
attaches runner to the internal monitoring network for Prometheus; it never joins
an external network and its HTTP port is not published.

These are defense layers for **reviewed** extensions, not a claim that vm or
Node permissions are a complete hostile-code sandbox. See the
[Node permission limitations](https://nodejs.org/docs/latest-v22.x/api/permissions.html#limitations-and-known-issues)
and ADR-003. Java must still enforce the review whitelist in M2.5/M2.6.

## Observability and tests

JSON metrics include per-operation counts, sum/max duration, cumulative
millisecond histogram buckets (10/50/100/500/1000/8000/+Inf), timeout count,
current workers and waiting queue length. Logs contain only requestId, op,
12-character digest prefix, elapsedMs and result code; no question/answer data.

`GET /v1/metrics/prometheus` adds Prometheus text format 0.0.4, using the same
`X-Internal-Token` check. The JSON endpoint is unchanged. No new dependency is
required; labels are bounded operation names (or `other`) and fixed stop reasons.

| Metric | Meaning |
| --- | --- |
| `qf_runner_queue_wait_seconds_bucket/sum/count{op}` | Monotonic time from entering the pool queue to worker assignment. One observation per dispatched request, including immediate assignment. Jobs rejected/expired/closed before assignment have no dispatch observation. |
| `qf_runner_execution_seconds_bucket/sum/count{op}` | From immediately before worker stdin write to receipt of a complete result frame or active termination. Includes IPC, worker-side serialization and cold worker boot/VM/schema setup; **not** pure hook CPU time. Parent-side input encoding and successful response parsing are excluded. Includes failed/terminated execution, once per dispatched request. |
| `qf_runner_worker_starts_total` | `spawn()` attempts returning a child handle, including asynchronous startup failure; not a count of ready workers. |
| `qf_runner_worker_stops_total{reason}` | Retirements, once per worker removed from the pool. Reasons: `lru`, `max_requests`, `timeout` (request or hook), `abnormal_exit` (unexpected exit/start failure/invalid output or excessive stderr), `idle`, `shutdown`, `output_limit`. A later child close event does not double count. |
| `qf_runner_workers` | Workers registered in the pool, busy plus idle. A removed worker may still be finishing its OS-level SIGKILL exit. |
| `qf_runner_queue_length` | Requests waiting for assignment, excluding active work and pre-pool body/file loading. |

Histogram bounds in seconds: 0.001/0.005/0.01/0.025/0.05/0.1/0.25/0.5/1/2.5/5/10/30/60/120/+Inf.
All operation/reason series are initialized to zero. Body reading and blob validation
occur before these pool timers; they remain included in JSON elapsedMs and Java
client timing. Separate P95s cannot simply be added to obtain an end-to-end P95.
Limits, priorities, per-package cap, LRU selection, request/idle timers and responses
are unchanged. See [loadtest monitoring](../loadtest/README.md) for scraping and panels.

`node --test` exercises real HTTP sockets and real Node worker processes. The
test-only legacy oracle and all five basic type examples are frozen from
`quizforge_web@4522bb8`; production does not import them and Docker excludes them.
Parity assertions compare complete successful `data` structures, not snapshots
of selected fields. Rule error text is deliberately sanitized in v1; semantic
error codes are preserved. Pool scheduling/recycling, protocol bounds and
adversarial failures have separate tests. All tests create disposable blob roots.
