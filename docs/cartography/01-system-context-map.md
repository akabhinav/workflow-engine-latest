# System Context Map — Tranto

Tranto is a **modular monolith that can deploy as a monolith or a cluster**. The *same*
`Executor` / `Worker` / `Scheduler` code runs in two wirings:

- **`StandaloneEngine`** — everything in one JVM, in-memory queues + repositories (used by the CLI `run` command and the webserver).
- **`DistributedEngine`** — the identical engine wired onto JDBC-backed queues + repositories so multiple nodes coordinate through a shared database (used by the CLI `server` command).

The central design claim (proven by `DistributedEngineTest`): components talk **only** through
`QueueInterface` and the repository interfaces, so swapping the transport changes nothing in the
executor or worker.

## Container diagram — Standalone wiring (in-process)

Legend: `[ ]` component · `( )` store · `<< >>` external actor · `-->` sync call · `==topic==>` async queue.

```
   << CLI user >>                      << REST/SSE client >>
        |                                    |
        | tranto run <flow.yaml>             | HTTP :8080  /api/v1/*
        v                                    v
   [ RunCommand ]                       [ Spring Controllers ]
   (cli module)                         (webserver module: Flow/Execution/Health/Metrics)
        |                                    |
        |  engine.run(flow,inputs)           |  engine.submit / subscribe / flows / executions
        +---------------+   +----------------+
                        v   v
                 [ StandaloneEngine ]  (core.runners) ---- register ----> [ Scheduler ] --tick 1s--> onFire=submit
                        |                                                      ^
                        | Execution.newExecution + InputResolver.resolve      | (cron due)
                        v                                                      |
             ==execution==>  [ Executor ]  ==worker-task==>  [ Worker ]  --run()--> [ RunnableTask plugin ]
                 (state machine, ExecutionPlanner)  <==worker-result==             (plugin-core)
                        |                                                             |
                        | save / read                                                | runContext.render / kv / storage
                        v                                                            v
                 ( MemoryExecutionRepository )                              ( MemoryKVStore )  ( LocalStorage: tmp/tranto-storage )
                 ( MemoryFlowRepository )
                        |
                        | ==execution-updated==>  onExecutionUpdated  --> FlowTriggerEvaluator.onTerminal
                        |                                             --> waiters.complete (blocking run)
                        |                                             --> metrics counters
                        v                                             --> SSE emitters (webserver "follow")
                 << downstream flows started by FlowTrigger >>
```

## Container diagram — Distributed wiring (cluster)

```
   << CLI: tranto server --role executor >>     << ...--role worker >>     << ...--role scheduler >>
              |                                        |                          |
              v                                        v                          v
     [ DistributedEngine(exec) ]              [ DistributedEngine(worker) ]  [ DistributedEngine(sched) ]
              |   \                                    |                          |
              |    \  same Executor/Worker/Scheduler classes as standalone       |
              v     v                                  v                          v
        +-----------------------------  ( Shared JDBC database: H2 file / Postgres / MySQL )  ----------------+
        |                                                                                                     |
        |  ( flows )   ( executions )   ( queue_messages: topics = execution | worker-task |                  |
        |                                              worker-result | execution-updated )                    |
        +-----------------------------------------------------------------------------------------------------+
              ^ nodes coordinate ENTIRELY through the queue_messages table (poll + optimistic claim, 25ms)

   NOTE: KV, LocalStorage, and concurrency-admission are still PER-NODE in distributed mode
         (see Landmine Register #3) — only queues + flow/execution repos are shared.
```

## Components

| Component | Type | Responsibility | Module | Confidence |
|-----------|------|----------------|--------|------------|
| `RunCommand` / `ServerCommand` / `TrantoCommand` | CLI entry | picocli command tree; `run` = standalone, `server` = distributed node | `cli` | High |
| `TrantoApplication` | CLI bootstrap | Spring Boot non-web app → delegates to picocli, maps exit code | `cli` | High |
| `TrantoServer` + 4 `@RestController`s | HTTP entry | Spring MVC REST + SSE on :8080 | `webserver` | High |
| `EngineConfig` | Wiring | Spring beans: registry, YAML parser, **`StandaloneEngine`** (web uses standalone + in-memory) | `webserver` | High |
| `StandaloneEngine` | Orchestrator | All-in-one engine; `run()` blocking, `submit()` async, `subscribe()`, `kill`, `resume`, `metrics` | `core` | High |
| `DistributedEngine` | Orchestrator | Same engine on JDBC queues/repos; role-split executor/worker/scheduler | `core` | High |
| `Executor` | State machine | Consumes executions + results, drives each execution to terminal; per-execution `ReentrantLock`; retries, kill, resume, subflow join, concurrency admission | `core` | High |
| `ExecutionPlanner` | Pure planner | Pure function over immutable `Execution`: walks the task tree, decides next task-runs / terminal / paused / waiting | `core` | High |
| `Worker` | Task runner | Executes `RunnableTask.run()` on virtual threads; enforces `runIf`, `disabled`, `timeout`, `allowFailure` | `core` | High |
| `Scheduler` | Time triggers | Per-(flow,trigger) next-fire tracking; 1s tick; fires cron `Schedulable` triggers | `core` | High |
| `FlowTriggerEvaluator` | Event triggers | On any terminal execution, starts flows whose `FlowListener` trigger matches upstream | `core` | High |
| `RunContextFactory` / `DefaultRunContext` / `VariableRenderer` | Templating | Assemble the variable map (`inputs`/`flow`/`execution`/`outputs`/`taskrun`), render Pebble | `core` | High |
| `InputResolver` | Validation | Coerce/validate/default flow inputs before any task runs | `core` | High |
| `PluginRegistry` / `PluginScanner` / `PluginClassLoader` / `PluginProcessor` | Plugin SPI | `type:`→class resolution; ServiceLoader discovery; child-first isolation for external JARs; build-time manifest generation | `core`, `processor` | High |
| `JacksonMapper` / `YamlFlowParser` / `PluginDeserializer` | Serde | YAML/JSON ↔ domain objects; polymorphic-by-`type` deserialization | `core` | High |
| Repositories (Flow/Execution) | Store facade | Memory + JDBC impls behind one interface | `core` | High |
| `QueueInterface` (Memory/Jdbc) | Transport | The message backbone; 4 logical topics | `core` | High |
| `KVStore` / `StorageInterface` | Store facade | Namespaced KV (TTL); filesystem object storage | `core`, `plugin-sdk` | High |
| 24 built-in plugins | Domain plugins | Tasks + triggers users compose (see Domain Glossary) | `plugin-core` | High |
| `plugin-sdk` | Contract | Semver-stable interfaces plugins compile against ("the stability wall") | `plugin-sdk` | High |
| `Slugify` | External plugin | Proof a 3rd-party plugin (`com.acme`) loads with zero engine change | `plugin-example` | High |

## Edges (how components connect)

| From | To | Mechanism | Sync/Async | Coupling risk | Evidence |
|------|----|-----------|-----------|---------------|----------|
| Controllers / RunCommand | `StandaloneEngine` | direct method call (Spring-injected / `new`) | Sync | Low | `EngineConfig.java:31`, `RunCommand.java:84` |
| Engine | `Executor` | `executionQueue.emit()` on topic `execution` | Async | Low | `StandaloneEngine.java:99`, `DistributedEngine.java:71` |
| `Executor` | `Worker` | topic `worker-task` (`WorkerTask`) | Async | Low | `Executor.java:314`, `Worker.java:37` |
| `Worker` | `Executor` | topic `worker-result` (`WorkerTaskResult`) | Async | Low | `Worker.java:62`, `Executor.java:81` |
| `Executor` | Engine/subscribers | topic `execution-updated` (state changes) | Async | Low | `Executor.java:308`, `StandaloneEngine.java:56` |
| `Executor` | itself (retry) | `RETRY_SCHEDULER.schedule()` delayed re-emit to `worker-task` | Async | Low | `Executor.java:266` |
| `Executor` (parent) | `Executor` (child) | subflow → new execution on `execution` queue; `subflowParents` map | Async | **Med** | `Executor.java:368-374`; correlation via in-memory `ConcurrentHashMap` |
| `Scheduler` | Engine | `onFire` callback → `submit(flow, {})` | Sync (in tick) | Low | `Scheduler.java:84`, `StandaloneEngine.java:51` |
| `FlowTriggerEvaluator` | Engine | `startFlow` callback → `submit(flow, {})` on terminal | Sync (in listener) | **Med** | `FlowTriggerEvaluator.java:47`, `StandaloneEngine.java:52` |
| All engine components | `flows` / `executions` | repository read/write | Sync | Low (mem) / see below (JDBC) | `Executor.java:90,289` |
| **JDBC nodes** | **`queue_messages` table** | poll every 25ms + 2-step optimistic claim (`SELECT WHERE consumed=FALSE` → conditional `UPDATE consumed=TRUE`) | Async | **HIGH — the only real inter-node wire** | `JdbcQueue.java:108-135` |
| **JDBC nodes** | `flows` / `executions` tables | `MERGE INTO` upsert; whole object as JSON `data` CLOB | Sync | **Med — shared mutable state, no row locking on read-modify-write** | `JdbcFlowRepository.java:36`, `JdbcExecutionRepository.java:35` |
| Plugin (`http.Request`) | external HTTP APIs | JDK `HttpClient` | Sync | Low | `plugin-core/.../http/Request.java` |
| Plugin (`jdbc.Query`/`Execute`) | external SQL DBs | `DriverManager` per task | Sync | Low | `plugin-core/.../jdbc/*.java` |
| Plugin (`script.Commands`→`Process`) | OS shell | `ProcessBuilder` (`cmd.exe`/`sh`) | Sync | Med (arbitrary command exec) | `plugin-core/.../runner/Process.java:43-62` |

## External systems

| System | Direction | Contract | Notes |
|--------|-----------|----------|-------|
| REST/SSE client (curl, UI-to-be) | inbound | `/api/v1/flows`, `/api/v1/executions`, `.../follow`, `/health`, `/metrics` | Non-blocking submit + SSE follow to terminal |
| CLI user / operator | inbound | `tranto run`, `tranto server` | |
| JDBC database (H2 / Postgres / MySQL) | bidirectional | `flows`, `executions`, `queue_messages` tables | H2 in-mem (default) or file (`AUTO_SERVER=TRUE`); Postgres/MySQL "portable but not wired" |
| Arbitrary HTTP APIs | outbound | via `http.Request` task | Non-2xx is **not** treated as failure |
| Arbitrary SQL DBs | outbound | via `jdbc.Query` / `jdbc.Execute` tasks | The example flows spin up their own H2 DBs |
| Local filesystem | bidirectional | `LocalStorage` under `${tmpdir}/tranto-storage`, `tranto:///` URIs | Path-traversal-guarded |
| External plugin JARs | inbound (load) | `plugin-sdk` interfaces + `META-INF/services` | `--plugins <dir>` (CLI) via child-first classloader |

## The one silent-coupling hotspot

The **`queue_messages` table is the entire distributed nervous system**. Every inter-node
interaction — dispatching work, returning results, notifying state changes, even starting subflows —
is a row in that one table, discriminated only by a `topic` string. It has **no visibility timeout
and no redelivery**: a message is marked `consumed=TRUE` *before* its consumer runs, so a node that
claims a message and then crashes loses that work silently. This is the highest-value thing to know
about the runtime and is detailed as **Landmine #1**.
