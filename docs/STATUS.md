# Tranto — Status: Done vs Not Done

> Honest, current snapshot of the Tranto (Kestra rebuild) implementation.
> Reference: Kestra is ~2,336 Java files built by a team over years — a faithful *working*
> rebuild is a multi-session effort. Everything below marked DONE is **built + verified green**
> (`mvn install` passes, tests pass, the app runs). Nothing is marked done on faith.

**Last verified:** full reactor `mvn clean install` = BUILD SUCCESS · 12 modules · **79 tests pass** ·
**first real integration plugins shipped: a script runner + AWS (SNS/SQS/Lambda/DynamoDB), each its own SDK-only module, auto-discovered via the generated ServiceLoader manifest** ·
flow triggers (event-driven flow-to-flow) work · REST API driven over real HTTP (MockMvc) ·
CLI runs example flows to SUCCESS · REST API + SSE work end-to-end · annotation processor
auto-generates the plugin manifest (23 built-ins) · custom third-party plugin discovered & run ·
JDBC (H2) persistence round-trips · **distributed executor+worker nodes coordinate through a shared DB** ·
**the JDBC queue is now at-least-once (lease + ack-after-success, crash-safe redelivery, purge)** ·
**executor nodes serialise the same execution cluster-wide (DB lock) and cron fires once via scheduler leader election** ·
**concurrency limits, KV, storage, and subflow-join are now shared cluster-wide (JDBC) — the last per-node state is gone** ·
**20 real-world + 10 complex DB workflows run and verify in-database** ·
**the full feature surface (incl. a complex SQL pipeline) also runs green on the JDBC distributed engine**.

**Rough completeness — ~80% of the core platform capability; ~35–40% of Kestra's total feature
surface** (the whole engine core AND supporting platform are in — scheduler + cron + flow triggers,
ServiceLoader plugin discovery, JDBC persistence + queue, **distributed executor/worker coordination
+ CLI server modes**, storage + KV, script + SQL tasks, REST + SSE + metrics, CI wall enforcement.
What remains is mostly *breadth* — the Vue UI and hundreds of integration plugins — plus
production-hardening of the distributed layer).

---

## ✅ DONE (built, tested, runnable)

### Foundation & build
- [x] Maven multi-module reactor (9 modules) + `platform` BOM
- [x] Java 21 (virtual threads), Spring Boot 3.3.5, Lombok, jOOQ-ready
- [x] `model` module: `@Plugin`/`@PluginProperty`/`@PluginSubGroup`/`@Example`/`@Metric` + enums (`StateType`, `Level`, `ServerType`)

### The stability wall (docs/08) — proven
- [x] `plugin-sdk` = the semver-stable contract plugins compile against
- [x] `plugin-core` (built-ins) compiles against SDK-only (`provided` scope) — can't see the engine
- [x] A parallel agent built 6 tasks against the SDK spec alone → the wall works for external authors

### Domain model (SDK)
- [x] `Flow`, `Task` (abstract), `Label`
- [x] `Execution`, `TaskRun`, `State` — immutable, functional `with*` transitions (replay-safe)
- [x] `NextTaskRun`, `RetryPolicy`
- [x] `Plugin`, `RunnableTask`, `FlowableTask`, `Output`/`VoidOutput`, `RunContext` (interface)

### Serialization
- [x] Jackson YAML + JSON mappers, field-based, ISO dates
- [x] **Polymorphic plugin resolution by `type`** via `PluginRegistry` + `PluginDeserializer` (nested tasks too)
- [x] Flow YAML round-trip (parse → object → YAML → parse)

### Templating
- [x] Pebble `VariableRenderer` + `DefaultRunContext` + `RunContextFactory`
- [x] `{{ inputs.x }}`, conditions, etc. (built-in Pebble filters/functions)

### Execution engine (the spine)
- [x] `QueueInterface` + in-memory queue (virtual-thread dispatch)
- [x] In-memory `FlowRepository` / `ExecutionRepository`
- [x] `Executor` — recursive state machine + per-execution `ReentrantLock`
- [x] `Worker` — runs `RunnableTask.run()` on virtual threads
- [x] `StandaloneEngine` — wires it all; `run()` (blocking) + `submit()` (async)

### Control flow (DAG)
- [x] `ExecutionPlanner` — arbitrary nesting, sequential vs parallel, branch selection
- [x] `Sequential`, `Parallel` (concurrent), `If` (then/else), `Switch` (cases/default)
- [x] **Loop / ForEach** — per-value iteration scopes (`{{ taskrun.value }}`), sequential or concurrent
- [x] **Subflow** (`ExecutableTask`) — parent spawns a child execution and mirrors its outcome

### Lifecycle control
- [x] **Pause / resume** — `Pausable` tasks pause the execution; `engine.resume(id)` continues it
- [x] **Kill** — `engine.kill(id)` cooperatively terminates a running execution → `KILLED`
- [x] **Timeout** enforcement — a task exceeding its `timeout` fails (worker-enforced, virtual thread)

### Error handling
- [x] **`errors:` / `finally:` hooks** — error handlers run on main failure; finally always runs

### Task semantics
- [x] `disabled` / `runIf` → SKIPPED
- [x] `allowFailure` → WARNING + execution-level warning rollup
- [x] **Retries** (`RetryPolicy.maxAttempts`) — executor re-dispatches failed attempts
- [x] **Retry backoff** — constant / exponential (`delay`, `maxDelay`) + overall `maxDuration` budget

### Flow model
- [x] **Typed Inputs** (`STRING/INT/FLOAT/BOOLEAN/DURATION/JSON`) — defaults, coercion, required validation
- [x] **Flow Outputs** — named Pebble expressions rendered at termination (from inputs + task outputs)
- [x] **Concurrency limits** — per-flow `limit` + `QUEUE`/`CANCEL`/`FAIL` behaviour on over-limit
- [x] **Task outputs in context** — `{{ outputs.<taskId>.<key> }}` available to downstream tasks

### Scheduler & triggers
- [x] **`AbstractTrigger`** model (polymorphic by `type`) + `triggers:` on the flow
- [x] **`Schedule`** cron trigger + self-contained 5-field `CronExpression` (no external lib)
- [x] **`Scheduler`** — deterministic `tick(now)` core + daemon timer; fires once per due window, arms on register
- [x] **`FlowTrigger`** (event-driven) — a flow starts automatically when another flow finishes (`FlowListener` SDK contract + `FlowTriggerEvaluator`); works on both engines

### Plugin discovery (docs/02)
- [x] **Annotation processor** (`processor` module) — writes `META-INF/services` for every `@Plugin` class at build time
- [x] **`PluginScanner`** — ServiceLoader discovery (21 built-ins auto-registered, no manual list needed)
- [x] **`PluginClassLoader`** — child-first isolation for external JARs (shared contract delegates to parent)

### Persistence (JDBC)
- [x] **`JdbcDatabase`** (H2 in-mem for local/dev; Postgres/MySQL-portable schema, JSON-blob + promoted columns)
- [x] **`JdbcFlowRepository`** / **`JdbcExecutionRepository`** — MERGE upserts, round-trip verified
- [x] **`JdbcQueue`** — table-backed queue with poller + optimistic claim (SKIP-LOCKED-ready for Postgres)

### Distributed runtime (JDBC-backed) — proven
- [x] **`DistributedEngine`** — the same executor/worker/scheduler wired onto JDBC queues + repositories (not in-memory)
- [x] **Split roles** — separate executor-only and worker-only nodes sharing one database coordinate to run a flow (`DistributedEngineTest`)
- [x] **CLI `server`** — `tranto server --role executor|worker|scheduler|all --jdbc-url <shared H2>` starts a node
- [x] **At-least-once JDBC queue** — a claim now only *leases* a row; the row is acked (`consumed`) *after* its consumer succeeds. A crashed/failed consumer's lease expires and the message is **redelivered** (no more silent loss); acked rows are **purged** (`JdbcQueueRedeliveryTest`)
- [x] **Cluster-wide per-execution lock** — `JdbcLock` (lease table) behind a `LockProvider`; two executor nodes can no longer process one execution concurrently (`DistributedHardeningTest` — no lost update)
- [x] **Scheduler leader election** — the same `JdbcLock` lease gates firing so a due cron trigger fires **exactly once** across N scheduler nodes; followers still advance their clocks so failover doesn't replay windows (`DistributedHardeningTest`)
- [x] **Shared cluster state (Landmine #3 closed)** — the last per-node state is now cluster-wide: `JdbcKVStore` (KV visible on every node), `JdbcStorage` (shared blob objects), `JdbcConcurrencyStore` (a `limit: N` flow admits at most N *cluster-wide*, FIFO queue + promote via a per-flow `JdbcLock`), and **durable subflow-join** (the parent ref is persisted on the child execution, so any node can join it — the in-JVM map is gone). Behind `KVStoreFactory`/`StorageInterface`/`ConcurrencyStore` seams; standalone keeps the in-memory defaults (`DistributedHardeningTest`)
- [x] Validates the core loose-coupling claim: swapping the transport changes nothing in the executor/worker

### Storage, KV, scripts
- [x] **`StorageInterface`** + **`LocalStorage`** (filesystem, path-traversal-guarded, `tranto://` URIs)
- [x] **`KVStore`** + **`MemoryKVStore`** (per-namespace, TTL) — reachable via `runContext.kv()` / `runContext.storage()`
- [x] **`TaskRunner`** SDK abstraction + **`Process`** runner (OS process, streamed output)

### Webserver & templating
- [x] **REST API** — create/list flows, trigger/list/poll executions, **health** + **metrics** endpoints; driven over real HTTP in `WebApiTest` (MockMvc)
- [x] **SSE follow** — `GET /api/v1/executions/{id}/follow` streams state to terminal via `SseEmitter`
- [x] **Engine metrics** — execution counts by terminal state (`GET /api/v1/metrics`)
- [x] **Pebble function library** — `now()`, `uuid()`, `json()`, `fromJson()` (+ task outputs in context)

### Quality / CI
- [x] **ArchUnit wall test** — plugins may not depend on engine internals (structural tripwire for docs/08)

### Built-in tasks & triggers (24)
- [x] `log.Log`, `debug.Return`, `debug.Echo`
- [x] `execution.Fail`, `execution.Exit`, `execution.Assert`, `output.OutputValues`
- [x] `flow.Sequential`, `flow.Parallel`, `flow.If`, `flow.Switch`, `flow.Sleep`, `flow.Loop`, `flow.Pause`, `flow.Subflow`
- [x] `script.Commands` (+ `runner.Process`), `kv.Set`, `kv.Get`, `trigger.Schedule`, `trigger.FlowTrigger`
- [x] **`jdbc.Query`** (rows/size/firstColumn/firstValue) + **`jdbc.Execute`** (multi-statement, transactional) — real SQL on any JDBC DB
- [x] `http.Request` (JDK HttpClient)

### Showcases proven end-to-end
- [x] **`RealWorldShowcaseTest`** — 20 real-world workflows run to their expected outcomes
- [x] **`ComplexDbWorkflowsTest`** — 10 complex DB pipelines, each verified in-database
- [x] **`DistributedShowcaseTest`** — the full feature surface (incl. a complex SQL pipeline) on the JDBC distributed engine

### API & entrypoints
- [x] `cli` — `tranto run <flow.yaml>` (`-i` inputs, `--register` subflows, `--plugins <dir>` external JARs) + `tranto server --role executor|worker|scheduler|all`
- [x] `webserver` — Spring MVC (no WebFlux): create/list flows, trigger/list/poll executions, **SSE follow**, **health**, **metrics**

### Tests (68, all green)
- [x] **`JdbcQueueRedeliveryTest`** (at-least-once: failed consumer → redelivery → ack), **`JdbcLockTest`** (cluster lock: exclusion, release, lease expiry), **`DistributedHardeningTest`** (no lost update across executor nodes; cron fires once across scheduler nodes; **KV/storage visible cross-node; cluster-wide concurrency limit; durable subflow-join**)
- [x] `StateTypeTest`, `YamlFlowParserTest`, `PebbleFunctionsTest` (now/uuid/json/fromJson)
- [x] `StandaloneEngineTest`, `FlowableEngineTest` (parallel + if/else), `TaskSemanticsTest`
- [x] `RetryTest`, `RetryPolicyTest`, `RetryBackoffTest`
- [x] `EngineFeaturesTest` (errors/finally, Loop, timeout), `EngineControlTest` (pause/resume, kill)
- [x] `SubflowTest`, `ConcurrencyTest`, `InputResolutionTest`, `FlowOutputTest`
- [x] `CronExpressionTest`, `SchedulerTest`, **`FlowTriggerTest`** (event-driven flow-to-flow)
- [x] `PluginScannerTest`, `SlugifyPluginTest` (third-party plugin), `CommandsTest`, `KvTaskTest`
- [x] `JdbcPersistenceTest`, **`DistributedEngineTest`** (split executor/worker over a shared DB)
- [x] `ExecutionSubscriptionTest`, **`WebApiTest`** (real HTTP via MockMvc), **`MetricsTest`**
- [x] `ArchitectureTest` (ArchUnit: the stability wall)

---

## ❌ NOT DONE (the remaining breadth + distributed infrastructure)

> Two categories dominate what's left, and both are genuinely large: the **Vue UI** (a whole
> frontend SPA) and the **hundreds of integration plugins** (each needs a real external SDK). These
> are not fabricated in a code session. The rest below is distributed-runtime infrastructure and
> long-tail features — all additive on the proven core.

### Frontend & integrations (the bulk of Kestra's file count)
- [ ] **Vue UI** — topology/DAG editor, run views, dashboards (entire separate frontend build)
- [ ] The **hundreds of integration plugins** (AWS/GCP/Azure/DB/messaging/notifications/…) — real vendor SDKs

### Scheduler & triggers (cron + flow triggers done; the rest not)
- [ ] **Polling / realtime triggers** (react to external events — queues, files, webhooks)
- [ ] **Backfills**, vNode sharding (scheduler leader election / trigger locking is now in — one node fires cron)

### Plugin system (discovery done; management not)
- [ ] **`PluginManager`** (install/download plugin JARs from a repo)
- [ ] **JSON-schema generation** for the NoCode editor

### Persistence (JDBC layer done; distributed extras not)
- [ ] **Executor state stores** (delay, queued, SLA, concurrency, worker-job-running) in JDBC
- [ ] jOOQ code-gen (currently hand-written JDBC), migrations, partitioning, `LISTEN/NOTIFY` wakeups
- [ ] `FOR UPDATE SKIP LOCKED` specialization on Postgres (H2 path uses lease + conditional-UPDATE claim; **at-least-once redelivery/leasing + purge are now done**)

### Distributed runtime (JDBC engine + at-least-once queue + cross-node exec lock + scheduler leader election + shared cluster state ALL DONE; production extras not)
- [ ] **Worker-controller** (gRPC job distribution, permit-based flow control) — current transport is the JDBC queue
- [ ] **Indexer** (batch log/metric persistence), service registry / heartbeats (queue redelivery now recovers dropped messages)
- [x] ~~Shared concurrency admission + KV + storage + subflow-join state~~ — **done**: `JdbcConcurrencyStore`, `JdbcKVStore`, `JdbcStorage`, persisted subflow parent-ref. Distributed mode is now safe to scale executor nodes.

### Webserver (flows/executions/health/metrics/SSE done; breadth not)
- [ ] Remaining controllers (logs, namespaces, secrets, triggers, dashboards, plugins)
- [ ] Pagination + `QueryFilter` DSL, OpenAPI, auth/CSRF/tenancy

### Runners, storage, engine long-tail
- [ ] **Docker / Kubernetes `TaskRunner`s** (Process runner is done; these need their daemons)
- [ ] **Namespace files** + cloud object-store `StorageInterface` (S3/GCS/Azure) — local FS, **shared JDBC storage**, and KV (memory + **shared JDBC**) are done
- [ ] **Delayed pause** (auto-resume), afterExecution tasks, breakpoints, restart/replay
- [ ] **`FILE`/`SELECT`/`SECRET`/`DATETIME` input types** (scalar types done); SLA, `pluginDefaults`, revisions
- [ ] Templating: `secret()`/`read()`/`kv()` functions, `jq`/`md5` filters (now/uuid/json/fromJson done)

### Quality / ops (the "better than Kestra" scorecard, docs/03)
- [ ] **japicmp** SDK API-diff CI gate (ArchUnit wall enforcement is done)
- [ ] Fuller metrics (Micrometer export) + tracing (OpenTelemetry) + per-task metrics (basic execution counters + health/metrics endpoints are in)
- [ ] **Benchmarks vs Kestra baseline** (all the doc/03 targets), Caffeine caching, native image

---

## Summary

```
  DONE      : foundation + plugin SPI/wall + serde + templating library + execution spine +
              DAG control flow (seq/parallel/if/switch/loop/subflow) + lifecycle
              (pause/resume/kill/timeout) + errors/finally hooks + retries + backoff +
              concurrency limits + typed inputs + flow outputs +
              scheduler/cron triggers + flow triggers (event-driven) +
              ServiceLoader plugin discovery (annotation processor + child-first classloader) +
              third-party plugin proven end-to-end + JDBC(H2) repositories & queue +
              distributed engine (split executor/worker over a shared DB) + CLI server modes +
              at-least-once JDBC queue (lease/ack/redelivery/purge) +
              cluster-wide per-execution lock + scheduler leader election +
              shared cluster state (JDBC KV + storage + concurrency limits + durable subflow-join) +
              local storage + KV + script/process runner + jdbc.Query/Execute (real SQL) +
              REST API (flows/executions/health/metrics) + SSE follow + engine metrics +
              ArchUnit wall gate + 24 tasks/triggers + CLI
  NOT DONE  : the Vue UI + hundreds of integration plugins (the bulk) +
              polling/realtime triggers + Postgres/MySQL wiring (SKIP LOCKED, jOOQ) +
              gRPC worker-controller + indexer + Docker/K8s runners +
              webserver breadth (pagination/auth/more controllers) + japicmp/benchmarks
```

The **execution-engine core and its supporting platform** (queue/executor mechanics, scheduler +
flow triggers, plugin discovery + isolation, JDBC persistence, **distributed executor/worker
coordination**, storage/KV, script + SQL running, REST + SSE + metrics, the stability wall) are
**built and proven** — including 20 real-world + 10 in-database + a distributed-engine showcase.
What remains is dominated by the **UI** and **integration breadth** — both inherently large — plus
production wiring of the distributed layer (Postgres/jOOQ, gRPC worker-controller, indexer). None of it
requires re-architecting what exists. Detailed roadmap in [`04-build-plan.md`](./04-build-plan.md);
progress log in [`PROGRESS.md`](./PROGRESS.md); operations in [`RUNBOOK.md`](./RUNBOOK.md).
