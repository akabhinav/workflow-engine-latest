# Use-Case Catalog — Tranto

Every entry point from the recon inventory, traced end-to-end. **13 entry points** (4 CLI-command +
9 HTTP handlers) plus **2 non-human triggers** (cron, flow-completion). Conventions: `[ ]` component,
`( )` store, `==topic==>` async queue, `-->` sync call.

Priority key: **★★★ deep / core**, **★★ deep**, **★ shallow**.

| ID | Use case | Trigger | Depth |
|----|----------|---------|-------|
| UC-01 | Run a flow to completion (CLI) | `tranto run` | ★★★ |
| UC-02 | Submit a flow asynchronously (REST) | `POST /executions/{ns}/{id}` | ★★★ |
| UC-03 | Follow an execution live (SSE) | `GET /executions/{id}/follow` | ★★ |
| UC-04 | Fire a flow on a cron schedule | Scheduler tick | ★★ |
| UC-05 | Start a flow when another finishes | Flow-completion event | ★★ |
| UC-06 | Run a subflow as a child execution | `Subflow` task reached | ★★ |
| UC-07 | Register/deploy a flow (REST) | `POST /flows` | ★ |
| UC-08 | Retry a failed task | worker result FAILED | ★★ |
| UC-09 | Pause & resume for manual approval | `Pause` task + `resume(id)` | ★ |
| UC-10 | Kill a running execution | `kill(id)` | ★ |
| UC-11 | Enforce a per-flow concurrency limit | over-limit submit | ★★ |
| UC-12 | Start a distributed node | `tranto server --role` | ★★ |
| UC-13 | List/inspect flows & executions | `GET` endpoints | ★ |
| UC-14 | Health & metrics | `GET /health`, `/metrics` | ★ |
| UC-15 | Load an external plugin JAR | `tranto run --plugins <dir>` | ★ |

---

## UC-01: Flow author runs a flow to completion (blocking) ★★★

- **Trigger:** CLI `tranto run <flow.yaml> [-i k=v] [--register sub.yaml] [--plugins dir] [-t sec]`
- **Actor:** flow author / operator at a terminal
- **Goal:** execute a workflow now and see its result + exit code
- **Preconditions:** valid YAML; referenced subflows passed via `--register`; external plugins via `--plugins`
- **Services touched:** RunCommand → StandaloneEngine → Executor → Worker → (Scheduler armed but idle)
- **Data touched:** MemoryFlowRepository (write), MemoryExecutionRepository (write), MemoryKVStore/LocalStorage (per task)
- **Flow:**
  1. Build `SimplePluginRegistry` + `CorePlugins.all()` (24 built-ins) (`RunCommand.java:57-60`).
  2. If `--plugins`: child-first `PluginClassLoader`, `PluginScanner.scan`, register discovered plugins (`:63-79`).
  3. `YamlFlowParser.parse(file)` → `Flow` (polymorphic tasks resolved by `type:`) (`:81-82`).
  4. `new StandaloneEngine()` (try-with-resources) — starts executor, worker, scheduler, update listener (`:84`).
  5. `engine.run(flow, inputs, timeout)`: `register` → `InputResolver.resolve` (defaults/coerce/required) → `Execution.newExecution` → register a `CompletableFuture` waiter → `executionQueue.emit(execution)` → block on `future.get(timeout)` (`StandaloneEngine.java:83-106`).
  6. Executor drives it (see UC-02 step chain / the planner loop) until terminal; `onExecutionUpdated` completes the waiter (`StandaloneEngine.java:184-196`).
  7. Print flow id, execution id, state, per-task states, outputs; **exit 0 iff state==SUCCESS else 1** (`RunCommand.java:92-105`).
- **Flow diagram:**
```
<< CLI user >>
    | tranto run flow.yaml -i email=x
    v
[ RunCommand ] --parse--> Flow --> [ StandaloneEngine.run ]
    |                                    | InputResolver.resolve (fail-fast -> FAILED if invalid)
    |                                    | Execution.newExecution (CREATED); waiters.put(future)
    |                                    v
    |                              ==execution==> [ Executor ] --plan(ExecutionPlanner)-->
    |                                    |                         dispatch leaf runs
    |                                    | ==worker-task==> [ Worker ] --run()--> plugin
    |                                    |                  <==worker-result== (SUCCESS/FAILED/WARNING/SKIPPED)
    |                                    | (loop: withTaskRun -> re-plan) ...
    |                                    | ==execution-updated==> onExecutionUpdated -> future.complete
    v                                    v
  print state + outputs            ( MemoryExecutionRepository )
  exit 0/1
```
- **Side effects:** whatever the tasks do (logs, SQL, HTTP, KV/storage writes, OS processes); flow outputs rendered at terminal.
- **Outcomes:** SUCCESS (exit 0) · WARNING (tolerated failure; exit 1 since != SUCCESS) · FAILED (exit 1) · `IllegalStateException` if not terminal within timeout (`StandaloneEngine.java:102`).
- **Evidence:** `cli/.../RunCommand.java`; `StandaloneEngine.run:83`; tests `StandaloneEngineTest`, `CommandsTest`, `RealWorldShowcaseTest`.
- **Open questions:** timeout throws rather than returning a FAILED execution — intentional? (Open Q #7)

---

## UC-02: REST client submits a flow asynchronously ★★★

- **Trigger:** `POST /api/v1/executions/{namespace}/{id}`
- **Actor:** REST client (script, UI-to-be)
- **Goal:** start a run without blocking; poll or follow for progress
- **Services touched:** ExecutionController → StandaloneEngine.submit → Executor → Worker
- **Data touched:** MemoryFlowRepository (read), MemoryExecutionRepository (write)
- **Flow:**
  1. `engine.flows().findById(null, namespace, id, null)` — **404** if unknown (`ExecutionController.java:38`).
  2. `engine.submit(flow, {})`: `register` → `InputResolver.resolve` → `Execution.newExecution` → **save** (persisted immediately so polling works) → `executionQueue.emit` → **return immediately** in CREATED (`StandaloneEngine.java:112-127`).
  3. Executor loop (async): `onExecution` → concurrency `admit` → `save` + `process`. `process` runs `ExecutionPlanner.plan`; dispatches leaf runs to `worker-task`, or opens flowable runs, or ends terminal (`Executor.java:85-93, 288-325`).
  4. Each `worker-result` → `onWorkerResult` → (retry? else) `process(execution.withTaskRun(result))` — re-plan (`Executor.java:177-188`).
  5. On terminal: `end` renders flow outputs, emits `execution-updated`, releases concurrency slot, joins any waiting parent subflow (`Executor.java:327-348`).
- **Flow diagram:**
```
<< REST client >>
    | POST /executions/finance/ledger_check
    v
[ ExecutionController.trigger ] --findById--> ( flows )   [404 if missing]
    | engine.submit
    v
[ StandaloneEngine.submit ] --save CREATED--> ( executions )   ==execution==> [ Executor ]
    |                                                                              |
    | return ExecutionResponse (CREATED/RUNNING) immediately                       | plan/dispatch/join
    v                                                                              v
<< client polls GET /executions/{id} or /follow >>                          ( executions ) updated to terminal
```
- **Side effects:** execution persisted; task side effects; `execution-updated` events feed metrics + SSE + flow-triggers.
- **Outcomes:** 200 with CREATED/RUNNING (terminal reached later, asynchronously) · 404 unknown flow.
- **Evidence:** `webserver/.../ExecutionController.java:35-41`; `StandaloneEngine.submit:112`; `Executor.process:288`; test `WebApiTest`.

---

## UC-03: Client follows an execution live over SSE ★★

- **Trigger:** `GET /api/v1/executions/{id}/follow`
- **Actor:** REST/UI client wanting real-time state
- **Goal:** stream every state change until the execution terminates, then close
- **Services touched:** ExecutionController.follow → StandaloneEngine.subscribe (→ `executionUpdatedQueue`)
- **Flow:**
  1. `SseEmitter(0L)` (no timeout) (`ExecutionController.java:63`).
  2. Look up current execution; **404** if absent; send current state as event `"execution"` (`:65-69`).
  3. If already terminal → complete & return (`:70-73`).
  4. Else `engine.subscribe(exec -> …)` — filter by id, emit each update, `complete()` on terminal (`:75-83`).
  5. `onCompletion`/`onError` unsubscribe the `AutoCloseable` (`:84-85`, `closeQuietly:99`).
- **Flow diagram:**
```
<< client >> --GET /executions/{id}/follow--> [ ExecutionController.follow ]
    ^                                              | subscribe(listener) on executionUpdatedQueue
    |  event: execution {state,taskRuns}           v
    +--------------------------------------  [ Executor ] ==execution-updated==> (fan-out to all subscribers)
                                                   | on terminal -> emitter.complete + unsubscribe
```
- **Side effects:** none (read-only stream). **Note:** in-memory fan-out — this is the "message-consumer" the recon flagged.
- **Outcomes:** stream of `execution` events ending at terminal · 404 unknown id.
- **Evidence:** `ExecutionController.java:61-105`; `StandaloneEngine.subscribe:163`; test `ExecutionSubscriptionTest`.
- **Open questions:** race — if the execution terminates between the `findById` snapshot and `subscribe`, is the terminal event missed? (Open Q #8)

---

## UC-04: A flow fires on its cron schedule ★★

- **Trigger:** wall-clock reaching a `Schedule` trigger's next fire time (Scheduler 1s tick)
- **Actor:** the Scheduler (non-human)
- **Goal:** start an execution automatically on a cron cadence
- **Flow:**
  1. At `register`, `Scheduler.register` seeds next-fire per (flow, `Schedulable` trigger) via `CronExpression.next`; re-register preserves the existing next-fire so a redeploy doesn't skip/double (`Scheduler.java:60-74`).
  2. Daemon `tick(now)` every 1s: for each entry with `nextFire <= now`, call `onFire(flow, trigger)` then advance `nextFire = nextEvaluationDate(now)` (`Scheduler.java:77-89`).
  3. `onFire` = `submit(flow, {})` → UC-02 execution loop.
- **Flow diagram:**
```
( registered flows w/ Schedule triggers )
    |  Scheduler.register -> next-fire per (flow,trigger)
    v
[ Scheduler ] --tick(now) every 1s--> if now >= nextFire: onFire --> [ Engine.submit ] ==execution==> [ Executor ]
                                              advance nextFire = cron.next(now)
```
- **Side effects:** a new execution per fire. **Outcomes:** execution created; `null` next-fire → trigger retired.
- **Evidence:** `Scheduler.java`; `plugin-core/.../trigger/Schedule.java`; `CronExpression.java`; tests `SchedulerTest`, `CronExpressionTest`.
- **Open questions:** single-node scheduler with no locking → every node in a cluster fires the same schedule (duplicate executions). (Landmine #4)

---

## UC-05: A flow starts when another flow finishes (event-driven) ★★

- **Trigger:** any execution reaching a terminal state
- **Actor:** FlowTriggerEvaluator (non-human), on behalf of a downstream flow with a `FlowTrigger`
- **Goal:** chain flows into pipelines without polling
- **Flow:**
  1. On every terminal execution, `onExecutionUpdated` → `flowTriggers.onTerminal(execution)` (`StandaloneEngine.java:194`).
  2. `FlowTriggerEvaluator` scans **all** registered flows; skips the source flow itself (cycle guard); for each `FlowListener` trigger, `matchesUpstream(namespace, flowId, state)` → `startFlow` = `submit` (`FlowTriggerEvaluator.java:33-52`).
  3. `FlowTrigger` matches on optional `namespace`/`flowId` (null = any) and a `states` list (default `[SUCCESS]`) (`FlowTrigger.java:47-58`).
- **Flow diagram:**
```
[ Executor ] --execution reaches terminal--> onExecutionUpdated --> [ FlowTriggerEvaluator.onTerminal ]
                                                                          | scan all flows (skip self)
                                                                          | FlowListener.matchesUpstream?
                                                                          v yes
                                                                    [ Engine.submit downstream flow ]
```
- **Side effects:** cascading executions. **Outcomes:** 0..N downstream executions started (one per matching flow; one trigger-match per flow suffices).
- **Evidence:** `FlowTriggerEvaluator.java`; `plugin-core/.../trigger/FlowTrigger.java`; test `FlowTriggerTest`.
- **Open questions:** `findAll()` scan on every terminal is O(flows) — fine now, unbounded later. Chains have no depth/cycle-across-flows guard beyond "not self" (Open Q #6).

---

## UC-06: A Subflow runs a child execution and returns its outcome ★★

- **Trigger:** the planner reaches a `Subflow` (`ExecutableTask`) task
- **Actor:** the parent execution
- **Goal:** compose flows; parent task-run mirrors the child's terminal state
- **Flow:**
  1. Planner emits the Subflow as a `NextTaskRun`; `process` sees `ExecutableTask` → `spawnSubflow` (`Executor.java:311-312`).
  2. `spawnSubflow`: `Execution.newExecution(subflowNamespace, subflowId, …)`, record `subflowParents[childId] = (parentExecId, parentTaskRunId)`, save child, `executionQueue.emit(child)` (`Executor.java:368-374`).
  3. Child runs as an ordinary execution. On child terminal, `end` finds the parent ref → `joinSubflow`: set the parent's Subflow task-run to the child's terminal state, re-plan the parent (`Executor.java:344-390`).
- **Flow diagram:**
```
[ Executor: parent ] --Subflow task--> spawnSubflow --> ( executions: child ) ==execution==> [ Executor: child ]
        ^                                    | subflowParents[child]=parent                        |
        | joinSubflow(childState)            |                                                     | ... to terminal
        +------------------------------------+-----------------------------------------------------+
          parent Subflow task-run := child terminal state; re-plan parent
```
- **Side effects:** a whole child execution. **Outcomes:** parent Subflow task-run = child's SUCCESS/FAILED/etc.; failure propagates through the parent's normal FAILED handling.
- **Evidence:** `Executor.spawnSubflow/joinSubflow`; `plugin-core/.../flow/Subflow.java`; test `SubflowTest`; examples `10-data-pipeline-parent/child.yaml`.
- **Open questions:** child inputs are hardcoded `Map.of()` (`Executor.java:370`) — subflows cannot yet receive parent inputs/outputs. **`subflowParents` is an in-memory map** → in distributed mode a subflow join breaks across nodes (Landmine #3).

---

## UC-07: Author registers/deploys a flow (REST) ★

- **Trigger:** `POST /api/v1/flows` (YAML body)
- **Flow:** `parser.parse(yaml)` → `engine.register(flow)` (save + arm triggers) → `FlowResponse`; parse failure → **400** (`FlowController.java:34-42`).
- **Side effects:** flow persisted (latest revision only); cron triggers armed. **Outcomes:** 200 `FlowResponse` · 400 bad YAML/unknown plugin type.
- **Evidence:** `FlowController.java`; `StandaloneEngine.register:140`.

---

## UC-08: Executor retries a failed task ★★

- **Trigger:** a `worker-result` in FAILED state for a task with a `RetryPolicy`
- **Flow:** `onWorkerResult` → `tryRetry`: if `attempts < maxAttempts` and within `maxDuration` budget, build a fresh SUBMITTED task-run (`attempts+1`), save, and re-emit to `worker-task` — after `delayForAttempt` (CONSTANT or EXPONENTIAL `delay·2^(n-1)` capped at `maxDelay`) via the shared `RETRY_SCHEDULER` (`Executor.java:226-271`).
- **Flow diagram:**
```
[ Worker ] ==worker-result FAILED==> [ Executor.onWorkerResult ] --tryRetry?-->
    attempts<max & within maxDuration ? --> new SUBMITTED run (attempts+1) --RETRY_SCHEDULER.schedule(delay)--> ==worker-task==>
    else --> join failure -> plan -> FAILED (or errors/finally)
```
- **Side effects:** repeated task attempts; delayed re-dispatch. **Outcomes:** eventual SUCCESS, or FAILED after budget/attempts exhausted.
- **Evidence:** `Executor.tryRetry`; `RetryPolicy.delayForAttempt`; tests `RetryTest`, `RetryBackoffTest`, `RetryPolicyTest`.

---

## UC-09: Pause for manual approval, then resume ★

- **Trigger:** planner reaches a `Pause` (`Pausable`) task; later `engine.resume(id)`
- **Flow:** planner returns `paused`; `process` sets execution + task-run PAUSED and persists (`ExecutionPlanner.startTask:172-177`, `Executor.process:299-302`). `resume` flips PAUSED task-runs to SUCCESS, execution to RUNNING, re-plans (`Executor.resume:208-220`).
- **Side effects:** execution parked indefinitely (no auto-resume/delay yet — see STATUS "delayed pause"). **Outcomes:** resumes to completion, or stays PAUSED forever if never resumed.
- **Evidence:** `Pause.java`; `Executor.resume`; test `EngineControlTest`. **Note:** no REST endpoint exposes `resume`/`kill` yet — engine-API only (Open Q #4).

---

## UC-10: Kill a running execution ★

- **Trigger:** `engine.kill(id)` (no REST endpoint yet)
- **Flow:** mark all non-terminal task-runs KILLED, execution KILLED, emit update, release concurrency slot; late worker results are dropped because the execution is already terminated (`Executor.kill:191-205`, `onWorkerResult:180`).
- **Outcomes:** KILLED (cooperative — a task already running on a worker finishes but its result is ignored). **Evidence:** `Executor.kill`; test `EngineControlTest`.

---

## UC-11: Enforce a per-flow concurrency limit ★★

- **Trigger:** submitting an execution for a flow whose `concurrency.limit` is already reached
- **Flow:** `admit` gates every new execution: under limit → take a slot; at limit → per `behavior` **QUEUE** (persist QUEUED, enqueue), **CANCEL** (→ CANCELLED), or **FAIL** (→ FAILED). On any terminal, `releaseSlot` admits the next QUEUED execution (`Executor.admit:101-134`, `releaseSlot:144-170`).
- **Flow diagram:**
```
==execution==> [ Executor.admit ] --under limit?--> take slot --> process
                     |  at limit:
                     +-- QUEUE  --> save QUEUED + enqueue --------> (later) releaseSlot -> RUNNING
                     +-- CANCEL --> CANCELLED (terminal)
                     +-- FAIL   --> FAILED (terminal)
```
- **Side effects:** executions parked/terminated by policy. **Outcomes:** QUEUED→RUNNING, CANCELLED, or FAILED.
- **Evidence:** `Executor.admit/releaseSlot`; `Concurrency.java`; test `ConcurrencyTest`. **Note:** admission state is a per-instance in-memory map → **not cluster-wide** (Landmine #3).

---

## UC-12: Operator starts a distributed node ★★

- **Trigger:** `tranto server --role executor|worker|scheduler|all --jdbc-url <url> [--for N]`
- **Flow:** parse role → open `JdbcDatabase.h2(url)` (creates schema) → `new DistributedEngine(db, mappers, exec, worker, sched)` wiring JDBC queues + repos → stay alive until `--for` elapses or shutdown hook (`ServerCommand.java:38-69`, `DistributedEngine.java:68-95`).
- **Flow diagram:**
```
<< operator >> tranto server --role worker --jdbc-url jdbc:h2:file:./tranto-db
    v
[ ServerCommand ] --> JdbcDatabase.h2 (initSchema) --> [ DistributedEngine(workerRole) ]
                                                          | Worker polls ( queue_messages topic=worker-task ) every 25ms
                                                          v runs tasks, writes worker-result rows
```
- **Side effects:** a long-lived node coordinating via shared DB. **Outcomes:** node runs until interrupted. **Evidence:** `ServerCommand.java`; `DistributedEngine.java`; tests `DistributedEngineTest`, `DistributedShowcaseTest`.

---

## UC-13 / UC-14 / UC-15: Inspection, ops, extensibility ★

- **UC-13 List/inspect:** `GET /flows` → `findAll`; `GET /flows/{ns}/{id}` → `findById` (404 if empty); `GET /executions` → `findAll`; `GET /executions/{id}` → `findById` (404). All read-only, in-memory. (`FlowController.java:45-56`, `ExecutionController.java:44-55`).
- **UC-14 Health/metrics:** `GET /health` → `{status:UP, flows:n, executions:m}`; `GET /metrics` → execution counts by terminal state + `total` (in-memory counters incremented in `onExecutionUpdated`). (`HealthController.java:25`, `MetricsController.java:24`, `StandaloneEngine.metrics:172`). **Note:** metrics count only terminal states and reset on restart (Open Q #9).
- **UC-15 External plugin:** `tranto run --plugins <dir>` → child-first `PluginClassLoader` over the dir's JARs → `PluginScanner` (ServiceLoader) → register. Proven by `Slugify` (`com.acme` package) loading with zero engine change. (`RunCommand.java:63-79`; `PluginScanner`; test `SlugifyPluginTest`).

---

## Coverage note

All 13 code entry points + 2 non-human triggers are mapped. No entry point is left unaccounted.
The engine-internal control operations (`kill`, `resume`) are reachable from code/tests but **not yet
exposed over REST or CLI** — recorded in Open Questions, not dropped.
