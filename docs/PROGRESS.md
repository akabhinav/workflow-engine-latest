# Tranto — Build Progress

Status of the implementation vs the phased plan (`04-build-plan.md`). Updated as we build.
**The platform is runnable today**: it executes YAML flows via CLI and REST API.

---

## Done and verified ✅

| Phase | What | Proof |
|-------|------|-------|
| 0 | Maven reactor, `platform` BOM, `model` (annotations + enums) | `mvn install` green; StateType tests |
| 1 | Domain model + polymorphic YAML serde (plugin resolves by `type`) | round-trip test green |
| 2 | Pebble `VariableRenderer`, `RunContext` + factory | rendering used by Log task |
| 3 | **The spine**: in-memory queue, repositories, `Executor` (sequential state machine + per-execution lock), `Worker`, `StandaloneEngine` | `hello_world` runs to SUCCESS; fail-fast test |
| — | `plugin-core` built-ins on the **SDK only** (proves the stability wall): `log.Log`, `debug.Return`, `execution.Fail` | build green (provided-scope SDK) |
| — | `cli` Spring Boot + picocli: `tranto run <flow.yaml>` | runs example flow → SUCCESS, exit 0 |
| 6 (partial) | `webserver` Spring MVC (no WebFlux): create flow, trigger execution, poll status | curl end-to-end → SUCCESS |
| 4 | **Flowable DAG control flow**: recursive `ExecutionPlanner` (nesting, sequential/parallel/branch); `Sequential`, `Parallel`, `If`, `Switch` tasks | nested Parallel + If/else tests green |
| 4 | **Task semantics**: `disabled`/`runIf` → SKIPPED, `allowFailure` → WARNING with execution-level warning rollup | allowFailure + runIf tests green |
| 4 | **Loop / ForEach** (per-value iteration scopes, `{{ taskrun.value }}`) + **Subflow** (`ExecutableTask`, parent mirrors child) | Loop + Subflow tests green |
| 4 | **Lifecycle**: pause/resume (`Pausable` + `engine.resume`), kill (`engine.kill` → KILLED), timeout enforcement (worker) | EngineControl + EngineFeatures tests green |
| 4 | **`errors:` / `finally:` hooks** — error handlers on main failure; finally always runs | hooks tests green |
| 4 | **Retries**: `RetryPolicy` + executor re-dispatch; **backoff** (constant/exponential, `maxDelay`, `maxDuration` budget) | RetryTest + RetryPolicyTest + RetryBackoffTest green |
| 1+ | **Typed Inputs** (STRING/INT/FLOAT/BOOLEAN/DURATION/JSON: defaults, coercion, required) + **Flow Outputs** (rendered at end) | InputResolution + FlowOutput tests green |
| 4 | **Concurrency limits** — per-flow `limit` + QUEUE/CANCEL/FAIL over-limit behaviour | ConcurrencyTest green (queue-then-admit, cancel) |
| 5 | **Scheduler + cron triggers**: `AbstractTrigger` model, `Schedule` + self-contained `CronExpression`, `Scheduler` (deterministic tick + daemon) | Cron + Scheduler tests green |
| 2 | **Automatic plugin discovery**: `processor` annotation processor writes `META-INF/services`; `PluginScanner` (ServiceLoader) + child-first `PluginClassLoader` | 21 plugins auto-discovered; scanner test green |
| 8 | **JDBC (H2) persistence**: `JdbcDatabase` + `JdbcFlowRepository`/`JdbcExecutionRepository` (MERGE upserts) + `JdbcQueue` (poller + optimistic claim) | repo + queue round-trip tests green |
| 7 | **Storage + KV + scripts**: `LocalStorage`, `MemoryKVStore` (wired into RunContext), `TaskRunner` + `Process` runner | Commands + KV tests green |
| 6 | **SSE follow** (`GET /executions/{id}/follow`) + **Pebble library** (`now`/`uuid`/`json`/`fromJson`) | subscription + Pebble tests green |
| 9 | **ArchUnit wall gate**: plugins may not reach engine internals (CI tripwire for docs/08) | ArchitectureTest green |
| — | **21 built-in tasks**: + `script.Commands`, `runner.Process`, `kv.Set`, `kv.Get`, `trigger.Schedule` on top of the 16 flow/exec tasks | build green |
| 8 | **At-least-once JDBC queue**: claim now *leases* a row; ack (`consumed`) only *after* the consumer succeeds; a failed/crashed consumer's lease expires → **redelivery**; acked rows **purged** | JdbcQueueRedeliveryTest green |
| 8 | **Cluster-wide per-execution lock**: `JdbcLock` lease table behind a `LockProvider`; two executor nodes can't process one execution concurrently (in-JVM lock still the standalone default) | DistributedHardeningTest (no lost update) |
| 5 | **Scheduler leader election**: same `JdbcLock` lease gates firing → a due cron fires **once** across N scheduler nodes; followers advance clocks so failover doesn't replay | DistributedHardeningTest + JdbcLockTest green |
| 8 | **Shared cluster state (Landmine #3 closed)**: `JdbcKVStore` (KV visible on every node), `JdbcStorage` (shared blobs), `JdbcConcurrencyStore` (a `limit:N` flow admits ≤N cluster-wide, FIFO promote under a per-flow `JdbcLock`), and **durable subflow-join** (parent ref persisted on the child, in-JVM map removed). Behind `KVStoreFactory`/`StorageInterface`/`ConcurrencyStore` seams; standalone unchanged | DistributedHardeningTest (KV/storage/concurrency/subflow cross-node) green |

**10 Maven modules, 68 tests, all green.** Run it:
```
mvn install
# CLI:
java -jar cli/target/tranto.jar run examples/hello_world.yaml
# Server (8080 may be busy → use another port):
java -jar webserver/target/tranto-server.jar --server.port=8085
curl -X POST localhost:8085/api/v1/flows -H 'Content-Type: text/plain' --data-binary @examples/hello_world.yaml
curl -X POST localhost:8085/api/v1/executions/dev/hello_world
curl localhost:8085/api/v1/executions/<id>
```

---

## Architecture guarantees already holding

- **The stability wall (docs/08):** `plugin-core` compiles against `plugin-sdk` only (scope
  `provided`) — it cannot see the engine. Adding plugins won't change the platform.
- **Immutable state + functional transitions:** `Execution`/`TaskRun`/`State` are immutable with
  `with*` copies — the basis for safe replay.
- **Loose coupling:** executor/worker talk only through `QueueInterface`; swapping the in-memory
  queue for JDBC won't touch them.
- **Virtual threads:** worker jobs and queue dispatch run on Java 21 virtual threads.

---

## Remaining (additive — the SDK wall means these don't disturb what's built)

| Phase | Work |
|-------|------|
| UI | **The Vue UI** — topology/DAG editor, run views, dashboards (a whole separate frontend build) |
| plugins | The **hundreds of integration plugins** (AWS/GCP/Azure/DB/messaging/…) — each needs a real vendor SDK |
| 5 | Polling / realtime triggers, backfills, vNode sharding (cron scheduler + **leader election** are done — one node fires cron) |
| 7 | Docker / Kubernetes `TaskRunner`s, namespace files, object-store storage (Process runner + local storage + KV done) |
| 8 | Distributed multi-process runtime: worker-controller (gRPC), indexer, heartbeats, jOOQ code-gen, `LISTEN/NOTIFY`, Postgres `SKIP LOCKED` (per-execution lock + at-least-once queue + scheduler leader election + **shared concurrency/KV/storage/subflow-join state are all done** — executors are safe to scale) |
| 6 | Remaining controllers (logs/namespaces/secrets/triggers/dashboards/plugins/metrics), pagination + `QueryFilter`, OpenAPI, auth/tenancy |
| 1+ | `PluginManager` (download JARs) + JSON-schema gen; SLA, `pluginDefaults`, revisions, FILE/SELECT/SECRET/DATETIME inputs; delayed pause, restart/replay |
| 9 | japicmp SDK API-diff gate, metrics/tracing (Micrometer/OTel), benchmarks vs Kestra baseline, caching, native image |

The critical, highest-risk pieces (queue/executor mechanics, the full DAG control flow incl.
loop/subflow, lifecycle control, retries/backoff, concurrency, scheduler, ServiceLoader plugin
discovery + isolation, JDBC persistence + queue, storage/KV, script runner, SSE, the SDK wall) are
**built and proven**. What remains is dominated by the **Vue UI** and **integration-plugin breadth**
— both inherently large — plus **distributed multi-process wiring**, all additive on stable foundations.
