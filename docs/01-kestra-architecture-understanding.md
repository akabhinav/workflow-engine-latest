# Kestra — Deep Architecture Understanding

> This is our reverse-engineered understanding of the existing Kestra codebase
> (`../kestra`), the system we are rebuilding in Spring Boot + Java 21 as **Tranto**.
> Source: full exploration of all 2,336 Java files across 77 Gradle modules.
> Read this before the target design (`02-*`) and plan (`04-*`).

---

## 0. What Kestra is, in one paragraph

Kestra is a **declarative, event-driven workflow orchestrator**. You write a **Flow**
in YAML (a list of **Tasks** + **Triggers**); Kestra turns each run into an
**Execution** (a list of **TaskRuns**, each with a **State**); a set of independently
scalable services — **executor**, **scheduler**, **worker** — coordinate that execution
through a **queue**, persisting everything via pluggable **repositories** and
**storage**. Tasks are **plugins** discovered at runtime. The signature engineering
achievement is that the whole distributed system runs on **just a relational database**
(no Kafka) by using the DB as both the message bus and the state store.

---

## 1. The domain model (the `core` + `model` modules)

The vocabulary every other module speaks:

| Concept | Class | Shape | Notes |
|--------|-------|-------|-------|
| **Flow** | `models/flows/Flow.java` (extends `AbstractFlow`) | mutable Lombok `@SuperBuilder` POJO | id + namespace + revision identity; owns `tasks`, `errors`, `_finally`, `afterExecution`, `triggers`, `inputs`, `outputs`, `labels`, `concurrency`, `sla`, `retry`, `pluginDefaults` |
| **Task** | `models/tasks/Task.java` (abstract, `@Plugin`) | mutable `@SuperBuilder` | base of every task; `id`, `type`, `timeout`, `retry`, `allowFailure`, `runIf`, … |
| **Execution** | `models/executions/Execution.java` | **immutable** `@Builder` + `@With` | `taskRunList`, `state`, `inputs`, `outputs`, `labels`, `trigger`, `parentId`. Functional updates via `withTaskRun(...)` |
| **TaskRun** | `models/executions/TaskRun.java` | immutable `@Builder` | one run of one task; owns its own `State` + `attempts`; `withState(...)`, `fail()`, retry logic |
| **State** | `models/flows/State.java` | immutable `@Value` | `current: Type` + append-only `histories`. **Type enum**: CREATED, SUBMITTED, RUNNING, PAUSED, RESTARTED, KILLING, SUCCESS, WARNING, FAILED, KILLED, CANCELLED, QUEUED, RETRYING, RETRIED, SKIPPED, BREAKPOINT, RESUBMITTED. Predicates: `isTerminated()`, `isRunning()`, … |
| **Input** | `models/flows/Input.java` | static `@JsonSubTypes` | closed set: STRING, INT, BOOL, FILE, JSON, SELECT, SECRET, DATETIME, … (~20 kinds) |
| **Output** | `models/flows/Output.java` (flow) / `models/tasks/Output.java` (task marker) | | flow outputs are rendered artifacts; task outputs are typed payloads |
| **Label** | `models/Label.java` | `record(key, value)` | serialized as list-of-pairs OR map |
| **Namespace** | plain `String` field (dotted, e.g. `company.team`) | | logical grouping/isolation; not a first-class entity |
| **Trigger** | `models/triggers/AbstractTrigger.java` (abstract, `@Plugin`) | `@SuperBuilder` | schedule/polling/realtime/flow triggers |

### The plugin abstraction (the extensibility core)
Everything loadable is a `io.kestra.core.models.Plugin`. Two orthogonal **capability
interfaces** decide *where* a task runs:
- **`RunnableTask<T extends Output>`** → `T run(RunContext)` — executed on the **Worker**.
- **`FlowableTask<T>`** → `resolveNexts()` / `resolveState()` — orchestrated by the **Executor** (control flow, no worker).
- **`ExecutableTask<T>`** → spawns **subflow** executions (Subflow task).

Triggers mirror this: `PollingTriggerInterface`, `RealtimeTriggerInterface`,
`Schedulable`, `WorkerTriggerInterface`.

### How YAML becomes objects (polymorphic deserialization)
- Task/Trigger/TaskRunner types are resolved **dynamically at deserialization time**:
  `plugins/serdes/PluginDeserializer` reads the `type:` field and looks the class up in
  the **`PluginRegistry`** — NOT compile-time `@JsonSubTypes`. This is what lets
  arbitrary plugin JARs contribute task types.
- Closed sets (Input, SLA, metrics) *do* use static `@JsonSubTypes`.
- `serializers/YamlParser` + `JacksonMapper` (YAML/JSON/Ion) with a `PluginModule`.

### Templating (Pebble)
- Tasks receive a **`RunContext`** giving them `render(...)`, `storage()`, `logger()`,
  `metric()`, `namespaceKv()`, secrets, plugin config.
- `VariableRenderer` wraps a Pebble engine, renders recursively (≤100 passes), with ~40
  custom functions (`kv`, `secret`, `read`, `fromJson`, `now`, …) and ~37 filters (`jq`,
  `md5`, `regexReplace`, …). Two block kinds: `{{ ... }}` and `{% ... %}`.

---

## 2. The runtime engine (executor / scheduler / worker / controller / indexer)

Kestra splits the runtime into **small deployable services that talk only through the
queue**. This is the key to horizontal scale.

### Executor — the flow state machine
- `DefaultExecutor` (service) + **`ExecutorService.process()`** (the state machine, ~1600 lines).
- Consumes `executionQueue` / `executionEventQueue` / `workerTaskResultQueue` (+ kill,
  subflow, loop, multiple-condition queues).
- **Per-execution serialization** via `ExecutionStateStore.lock(id, fn)` (a DB row lock +
  transaction) plus batching-by-executionId — different executions run in parallel,
  the same execution is processed serially.
- The state machine is an ordered pipeline over a mutable `ExecutorContext`:
  `handleRestart → handleEnd → handleKilling → handleNext → handleAfterExecution →
  handleWorkerTasks → handleFlowableTasks → handleExecutionUpdatingTasks →
  handleExecutableTasks`.
- For **flowable** tasks it computes next task-runs itself (`FlowableUtils`). For
  **runnable** tasks it emits a `WorkerTask` to the `workerJobEventQueue`.
- Terminal sink `toExecution(...)`: fires flow-triggers, handles concurrency-queue
  pop, SLA purge, subflow-end, emits `ExecutionEvent(TERMINATED/UPDATED)` +
  `FollowExecutionEvent` (for UI live-follow) + `TriggerExecutionTerminated` (unlock scheduler).
- Two 1s scheduled loops: `executionDelayLoop` (retries / paused-resume / WaitFor) and
  `executionSLAMonitorLoop`.

### Worker — runs the actual tasks
- `AbstractWorker` = `JobFetcher` (in) + `WorkerJobExecutor` (N virtual-thread consumers)
  + `WorkerIOSender`s (out). `WorkerTaskCallable` is where **`RunnableTask.run()`** actually
  runs (interruptible / timeout-able / killable).
- Task lifecycle: killed-check → CREATED→RUNNING → build RunContext → **task-cache** lookup
  (SHA-256 short-circuit) → run attempt (emit RUNNING marker) → post-process
  (allowFailure→WARNING) → emit terminal `WorkerTaskResult` + upload cache.
- Two deployment shapes:
  - **Cluster:** `WorkerAgent` connects to the controller over **gRPC** with **permit-based
    flow control** (`WorkerJobFetcher`).
  - **Local/standalone:** `SystemWorker` subscribes **directly** to the queue
    (`DirectQueueJobFetcher`), no gRPC.

### Worker-controller — job distribution (cluster only)
- `WorkerJobDispatcher`: one paused `QueueSubscriber` per **Worker Queue**; resumes only
  when a connected worker has permits + bucket capacity; `findAndReserveWorker`
  (atomic permit CAS, least-loaded). Persists `WorkerJobRunning` **before** dispatch for
  recovery. Broadcasts kill/cluster events down worker streams. **Jobs are never lost.**

### Scheduler — triggers & cron
- `DefaultScheduler` + `TriggerScheduler`, sharded by **virtual nodes (vNodes)** for
  horizontal scale. 1s loop.
- **Schedulable (cron)** fires → builds an Execution → emits to `executionQueue`.
- **Polling/realtime** triggers → dispatched as `WorkerTrigger` jobs (polling runs *on the
  worker*, not the scheduler); worker returns `TriggerEvaluated` to unlock.

### Indexer — offload logs/metrics
- `DefaultIndexer` batch-subscribes `logQueue` + `metricQueue` → repository `saveBatch`.
  Keeps high-volume writes off the hot execution path.

### End-to-end flow
```
Scheduler(cron/trigger) ──emit Execution──► executionQueue
                                              │
                                     Executor (lock + state machine)
                                              │  emit WorkerTask
                                              ▼
                              workerJobEventQueue (keyed by Worker Queue)
                                              │
                     Controller (permits) ──gRPC──► Worker.run(RunContext)
                     (local: direct queue)                │
   workerTaskResultQueue ◄── WorkerTaskResult ────────────┤
   logQueue / metricQueue ◄── logs/metrics ──► Indexer    │
        │                                                 │
   Executor (join result, loop until terminal) ──► ExecutionEvent(TERMINATED)
        └──► FollowExecutionEvent ──► Webserver SSE (UI live follow)
```

---

## 3. The queue + persistence layer (the "no-Kafka" magic)

**One relational database is both the message bus and the state store.**

### Queue abstraction (`core/queues` + `queue`)
Event-typed queues, each carrying a marker interface:
- **`DispatchQueueInterface`** — work queue, one message → one competing consumer.
- **`KeyedDispatchQueueInterface`** — dispatch partitioned by routing key (worker queues).
- **`VNodeDispatchQueueInterface`** — dispatch partitioned by consistent-hash vNode (scheduler).
- **`BroadcastQueueInterface`** — fan-out, every subscriber sees every message.

Physical queue name derived from the event class name. Catalogue: `executionQueue`,
`executionEventQueue`, `workerTaskResultQueue`, `workerJobEventQueue` (keyed),
`triggerEventQueue` (vNode), `killQueue` (broadcast), `logQueue`, `metricQueue`, etc.

### JDBC queue (`queue-jdbc`) — the crux
A single `queues` table: `offset (auto-inc PK), type, routing_key, key, value (JSONB), created`.
- **Work queue consume** = `SELECT … WHERE type=? ORDER BY offset LIMIT n FOR UPDATE SKIP
  LOCKED` → process → `DELETE`, all in one transaction. `SKIP LOCKED` lets **many nodes
  poll the same table** and each grabs a disjoint batch — horizontal scale, no coordinator.
  Rollback on failure = redelivery = **at-least-once**.
- **Broadcast consume** = each subscriber keeps its own in-memory `offset` cursor,
  `SELECT … WHERE offset > cursor` (no lock, no delete). A retention job reclaims old rows.
- **Adaptive polling** (`QueuePoller`): 25ms→500ms backoff, immediate re-poll on full
  batch → Kafka-like latency at peak, bounded DB cost when idle.

### Repositories (`jdbc`, jOOQ)
- **JSON-blob + generated-columns** pattern: every table is `key PK, value JSON(B), …
  generated columns projected from the JSON` (e.g. `state_current`, `namespace`,
  `tenant_id`, `deleted`) with matching indexes. Writes only set `key`+`value`.
- Two-layer base: `AbstractJdbcRepository` (persistence primitive) + `AbstractJdbcCrudRepository`
  (CRUD + soft-delete + tenant filter). Per-entity abstract repos (Flow/Execution/Trigger/…),
  concrete per dialect (H2/Postgres/MySQL).
- Core interfaces in `core/repositories/*` (tenant-first signatures).
- Executor **state stores** (delay, queued, SLA, concurrency, worker-job-running) are also
  JDBC — scheduling state survives restarts.

### Storage (`storage-local`)
- `StorageInterface` — file-like ops on `kestra://` URIs, tenant + namespace aware
  (task I/O, flow files, namespace files, KV blobs). `LocalStorage` = local FS under
  `basePath/<tenantId>/…` with path-traversal hardening. (S3/GCS/Azure are plugins.)

### Multi-tenancy & soft-delete
- Generated `tenant_id` + `deleted` columns on every table; `defaultFilter` always ANDs
  `tenant_id` + `deleted=false`, and both sit leftmost in composite indexes.

### Table catalogue (~25 tables)
`queues, flows, executions, triggers, logs, metrics, multipleconditions, executordelayed,
execution_queued, sla_monitor, settings, flow_topologies, service_instance,
worker_job_running, concurrency_limit, dashboards, kv_metadata, namespace_file_metadata,
locks, task_outputs, mcp, mcp_session`.

---

## 4. The API + CLI layer (webserver / cli)

### Webserver (Micronaut REST + Vue UI)
- Tenant-scoped base path `/api/v1/{tenant}/...`. Controllers hold **no business logic** —
  they delegate to services.
- Main controllers: **FlowController** (CRUD + validate + graph + export/import + bulk),
  **ExecutionController** (~2500 lines — trigger, restart/replay/kill/resume/pause, webhooks,
  files, SSE follow), **TriggerController** (backfill, unlock, disable), **LogController**
  (search + SSE follow), Namespace/NamespaceFile/Secret/KV, Dashboard, Metric, Plugin
  (schemas/icons/docs), Blueprint, Expression, Misc/Tenant, AI, MCP.
- **SSE streaming**: `executions/{id}/follow`, `logs/{id}/follow` (Reactor `Flux`).
- Pagination `PagedResults<T>` (`{results, total}`); uniform `QueryFilter` DSL;
  OpenAPI via Swagger annotations served as a static spec; Vue UI served as static
  resources with a `StaticFilter` that injects base path / CSRF.

### CLI (picocli + Micronaut)
- `Kestra` entrypoint → `server` subcommands set `kestra.server-type` and block forever.
- **`server local` / `server standalone`** = all-in-one single JVM (executor + worker +
  scheduler + indexer + controller + webserver; `local` also forces H2 + local storage — the
  zero-config demo mode).
- **Distributed subcommands** (`executor`, `worker`, `scheduler`, `indexer`, `controller`,
  `webserver`) each run one role in its own JVM, wired via the shared queue + DB.
- Other command groups: `flows`, `plugins` (install/list), `sys`, `configs`, `namespace`,
  `migrate`.

---

## 5. Plugin system, script execution, run-context (details)

- **Discovery** = Java `ServiceLoader`. The `processor` module's annotation processor writes
  `META-INF/services/io.kestra.core.models.Plugin` at compile time; `PluginScanner`
  enumerates them at runtime. Core built-ins scanned from the app classloader; external
  plugin JARs loaded from a plugins dir via child-first **`PluginClassLoader`** (isolated,
  with a shared-package excludes list). `PluginManager` installs/downloads JARs (Maven).
- **Built-in core plugins** (`io.kestra.plugin.core.*`, ~120 classes): flow (Sequential,
  Parallel, Switch, If, Loop, LoopUntil/WaitFor, Dag, AllowFailure, Subflow, Pause, Sleep,
  WorkingDirectory), trigger (Schedule, ScheduleOnDates, Flow, Webhook), log, debug (Return),
  execution (Fail, Exit, Assert, Labels, SetVariables, Purge…), http, storage, kv, namespace,
  output, metric, templating, runner (Process), preview renderers, dashboard.
- **Script/task-runner** abstraction (`script` module): `AbstractExecScript` (shell/python/…)
  → `CommandsWrapper` (stages input/output files, renders commands, replaces internal-storage
  URIs) → **`TaskRunner`** (`Process` local, `Docker`; K8s etc. are plugins). Cross-OS handling
  (`TargetOS`), log-line parsing for captured outputs.
- **`@Plugin` / `@PluginProperty`** annotations drive metadata + JSON schema for the NoCode UI
  editor (`dynamic`, `secret`, `group`, `hidden`, examples, metrics, aliases, priority).

---

## 6. What makes Kestra hard to clone (the crown jewels)

1. **The JDBC queue** (`FOR UPDATE SKIP LOCKED` + adaptive poll + broadcast cursors). This is
   the single most important, most differentiated piece.
2. **The executor state machine** (`ExecutorService.process` + `FlowableUtils`) — correct
   handling of flowables, retries, pauses, loops, subflows, concurrency, SLA, kill.
3. **Exactly-once-ish execution semantics** on at-least-once delivery (idempotent state joins
   under per-execution locking).
4. **The plugin model** (runtime type resolution, classloader isolation, JSON-schema gen).
5. **Pebble templating** with the full function/filter library.
6. **The permit-based worker flow control** (backpressure that never drops jobs).

Everything else (controllers, repositories, storage, most plugins) is comparatively
mechanical once these six are right.
