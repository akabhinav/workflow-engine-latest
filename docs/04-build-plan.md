# Tranto — Build Plan (phased, dependency-driven)

> How we build the Kestra clone in Spring Boot + Java 21 without missing features.
> Ordered by dependency, not calendar. Each phase ends at a demoable milestone.
> Read `01` (understanding) and `02` (target architecture) first.

---

## Guiding rule: vertical slice first, then breadth

We do NOT port module-by-module top-to-bottom. We build a **thin vertical slice** that runs
one real flow end-to-end as early as possible (Phase 3), then widen. This de-risks the hard
parts (queue, state machine) before investing in the long tail (plugins, UI polish).

The "team of 1000 architects" maps to **parallel workstreams once the spine exists** — after
Phase 3, plugins, controllers, dialects, and runners can be built concurrently by independent
squads because they only touch stable interfaces.

---

## Phase 0 — Foundations & scaffolding
**Goal:** the monorepo builds and boots an empty Spring Boot app.
- Maven multi-module reactor skeleton (§4 of doc 02): root `pom.xml` (`packaging=pom`), `platform`
  BOM (`<dependencyManagement>` importing the Spring Boot BOM), Java 21 toolchain
  (`maven-compiler-plugin` release 21), `spring.threads.virtual.enabled=true`, base
  `application.yml` (`tranto.*` config namespace).
- `model` module: `@Plugin`, `@PluginProperty`, and the core enums (`State.Type`, `Input.Type`,
  `Level`, `ServerType`). Zero dependencies.
- CI: build + unit-test + spotless. Spring Boot Maven plugin builds the runnable `cli` jar. Docker base image.
**Milestone:** `mvn verify` green; `java -jar cli/target/*.jar … --help` runs.

## Phase 1 — Domain model + serialization
**Goal:** parse a Kestra YAML flow into typed objects and back.
- `core/models`: `Flow`, `AbstractFlow`, `Task`, `Execution`, `TaskRun`, `State`, `Input`
  (+ subtypes), `Output`, `Label`, `AbstractTrigger`, `ResolvedTask`, `NextTaskRun`.
  Immutable Execution/TaskRun/State with `with*` methods.
- `core/serializers`: `JacksonMapper` (YAML/JSON/Ion), `YamlParser`, `PluginModule` +
  `PluginDeserializer` (dynamic `type:` resolution).
- Jakarta validation on the model.
**Milestone:** round-trip a real Kestra flow YAML → `Flow` object → YAML; unit tests over
sample flows copied from `../kestra/core/src/test/resources`.

## Phase 2 — Plugin system + RunContext + templating
**Goal:** discover tasks and render Pebble expressions.
- `core/plugins`: `PluginRegistry`, `DefaultPluginRegistry`, `PluginScanner` (ServiceLoader),
  `PluginClassLoader` (child-first, excludes list), `PluginResolver`, `PluginManager`.
- `processor`: annotation processor writing `META-INF/services/io.tranto.core.models.Plugin`.
- `core/runners`: `RunContext` + `DefaultRunContext` + `RunContextFactory`; `VariableRenderer`
  wrapping Pebble; port the function/filter library (start with the ~15 most-used, complete later).
- `plugin-core` seed: `Log`, `Return`, `Fail` (enough to test the engine).
**Milestone:** register the core bundle; render `{{ inputs.x }}` and `{{ now() }}`; a `Log`
task's `run(RunContext)` executes in a plain unit test.

## Phase 3 — THE SPINE: JDBC queue + repositories + executor + in-process worker ⭐
**Goal:** run one real flow end-to-end, single JVM, against H2 — the make-or-break phase.
- `core/queues` interfaces (Dispatch/Keyed/VNode/Broadcast) + `QueueService`.
- `queue-jdbc`: the `queues` table + `JdbcQueueClient` with `FOR UPDATE SKIP LOCKED` work-queue
  consume and broadcast-cursor consume; `QueuePoller` adaptive backoff.
- `jdbc` + `jdbc-h2`: `AbstractJdbcRepository` (JSON-blob + generated columns), Flow/Execution
  repositories, migrations runner, H2 baseline schema.
- `executor`: `DefaultExecutor` + `ExecutorService.process()` state machine (start with
  sequential flows: `handleNext`, `handleWorkerTasks`, `handleEnd`) + `ExecutionStateStore.lock`.
- `worker` (SystemWorker path only): `WorkerJobExecutor` on virtual threads, `WorkerTaskCallable`
  runs `RunnableTask.run`, emits `WorkerTaskResult`.
- Wire it: submit an `Execution` → executor emits `WorkerTask` → worker runs `Log` → result
  joins back → execution SUCCESS.
**Milestone:** the `hello_world` flow (Log task) runs to SUCCESS end-to-end in one process on
H2. **This proves the entire architecture.** Port Kestra's `H2RunnerTest` as the gate.

## Phase 4 — Flowable/control-flow tasks + retries/pauses/subflows
**Goal:** real orchestration, not just linear task lists.
- `FlowableTask` + `FlowableUtils`; `handleFlowableTasks` / `handleExecutableTasks` in the
  state machine; `ExecutionDelay` + `executionDelayLoop`.
- `plugin-core/flow`: Sequential, Parallel, Switch, If, Loop, LoopUntil/WaitFor, Dag,
  AllowFailure, Pause, Sleep, WorkingDirectory, **Subflow** (ExecutableTask).
- Retries (`nextRetryDate`), timeouts, `runIf`, `allowFailure`/`allowWarning`, kill (`killQueue`
  broadcast), concurrency limits, breakpoints.
**Milestone:** parallel + switch + loop + subflow + retry-on-failure flows all pass; port
Kestra's executor/runner test suite.

## Phase 5 — Scheduler + triggers
**Goal:** flows start themselves.
- `scheduler`: `DefaultScheduler` + `TriggerScheduler`, vNode sharding, cron/schedule eval.
- Triggers: `Schedule`, `ScheduleOnDates`, `Flow` (flow-trigger + conditions), `Webhook`,
  polling triggers (dispatched as worker jobs). Backfills. Trigger state store + locking.
**Milestone:** a cron `Schedule` trigger fires an execution on time; a `Flow` trigger reacts to
another flow's completion; backfill works.

## Phase 6 — Webserver REST API + SSE + serve the Vue UI
**Goal:** the product has a UI and API.
- `webserver`: Spring `@RestController`s reproducing the `/api/v1/{tenant}/...` contract —
  FlowController, ExecutionController (trigger/restart/replay/kill/resume/pause/webhook/files),
  TriggerController, Log/Namespace/Secret/KV/Dashboard/Metric/Plugin/Expression/Misc.
- Services layer (no logic in controllers); `PagedResults`, `QueryFilter` DSL, OpenAPI.
- SSE follow endpoints (executions/logs) via `FollowExecutionEvent` broadcast queue, implemented
  with Spring MVC `SseEmitter` on a virtual thread (no WebFlux/Reactor).
- Serve the **existing Kestra Vue UI** as static resources (StaticFilter equivalent: base path
  + CSRF injection). Keep the API contract identical so it "just works".
**Milestone:** open `http://localhost:8080`, build a flow in the UI, run it, watch live logs.

## Phase 7 — Script/task-runner + Docker + storage + KV/namespace files
**Goal:** run scripts in any language, in containers.
- `script`: `AbstractExecScript`, `CommandsWrapper`, `TaskRunner`, `Process` (local) + `Docker`
  runner; `TargetOS`, log-line output capture, input/output files.
- `storage-local` full `StorageInterface`; KV store; namespace files.
- `plugin-core` completion: http (Request/Download/SseRequest/Trigger), storage tasks, kv tasks,
  namespace tasks, execution tasks (Assert/Labels/SetVariables/Purge), metric, templating,
  preview renderers, dashboard charts/data.
**Milestone:** a Python/shell script task runs in Docker with input & output files; internal
storage + KV + namespace files all work.

## Phase 8 — Cluster mode: distributed services + Postgres + controller
**Goal:** production topology at scale.
- `jdbc-postgres` (native fulltext, JSONB, partitioning) + `jdbc-mysql`.
- `worker-controller`: gRPC `WorkerJobDispatcher` with permit-based flow control;
  `WorkerAgent` (gRPC fetcher/sender) for remote workers; `WorkerJobRunning` recovery.
- CLI distributed subcommands: `server executor|worker|scheduler|indexer|controller|webserver`.
- `indexer` for logs/metrics; service registry/heartbeats; multi-tenancy hardening.
**Milestone:** run each service in its own container against Postgres; scale workers to N
instances; kill/restart any service with no lost jobs.

## Phase 9 — Feature-parity long tail + hardening
**Goal:** "not missing any feature."
- Complete the Pebble function/filter library (all ~40 + ~37).
- SLA monitoring, execution labels/metadata, task cache, asset lineage, dependencies graph,
  flow topology, blueprints, dashboards, expression evaluation API.
- Migration tooling (`migrate` command), config schema generation, `sys` commands.
- Security: authn/authz filters, CSRF, secret management, tenant isolation tests.
- Observability: Micrometer metrics, OpenTelemetry tracing, health endpoints.
- Performance: partitioning, caching, JMH benchmarks (port `jmh-benchmarks`), load testing to
  validate the concurrency targets.
**Milestone:** a feature-parity checklist (derived from doc 01) is 100% green; benchmark suite
meets latency/throughput targets.

---

## Parallelization map (after Phase 3, squads run concurrently)

| Workstream | Depends on | Can start after |
|-----------|------------|-----------------|
| Control-flow tasks (Ph 4) | core engine | Phase 3 |
| Scheduler/triggers (Ph 5) | queue + executor | Phase 3 |
| Webserver + UI (Ph 6) | repositories + queue | Phase 3 |
| Script/Docker runners (Ph 7) | RunContext + worker | Phase 3 |
| Postgres/MySQL dialects (Ph 8) | `jdbc` base | Phase 3 |
| Controller/gRPC cluster (Ph 8) | queue + worker | Phase 4 |
| Pebble library + plugins (Ph 9) | RunContext | Phase 2 |

The **critical path** is Phase 0→1→2→3. Everything else fans out from the spine.

---

## Risk register (build the hardest things first, adversarially test them)

| Risk | Where | Mitigation |
|------|-------|------------|
| JDBC queue correctness under concurrency | Ph 3 | property-based + chaos tests; N-node `SKIP LOCKED` contention test from day one |
| Executor idempotency (at-least-once replay) | Ph 3–4 | deterministic replay tests; inject duplicate/reordered messages |
| State-machine edge cases (kill during pause during retry…) | Ph 4 | port Kestra's entire executor test corpus verbatim |
| Micronaut→Spring conditional-bean wiring gaps | Ph 3+ | one `@ConditionalOnProperty` matrix test per backend combo |
| Plugin classloader isolation | Ph 2 | port Kestra's classloader tests; verify shared-package excludes |
| Losing the Vue UI contract | Ph 6 | contract tests asserting response shape == Kestra's OpenAPI spec |

---

## Definition of done

1. Every flow in Kestra's own test resources runs identically on Tranto.
2. The unmodified Kestra Vue UI works against Tranto's API.
3. Distributed mode scales linearly and loses no jobs under fault injection.
4. Feature-parity checklist (doc 01 §6 + the full task catalogue) is complete.
5. Benchmarks meet the concurrency/latency targets.
