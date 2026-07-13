# Tranto — Detailed Architecture (LLD)

> The engineer's view. Component internals, interfaces, schemas, threading, transactions,
> and sequence flows. Sits under `05-hld.md` (the simple picture). Dash diagrams only.
> Stack: Spring Boot 3.x · Java 21 (virtual threads) · Spring MVC (no WebFlux) · Maven ·
> jOOQ · Pebble · package root `io.tranto`.

---

## 0. Layered view (where every piece sits)

```
  +-----------------------------------------------------------------------+
  |  ENTRYPOINTS      cli (picocli)            webserver (Spring MVC)      |
  +-----------------------------------------------------------------------+
  |  SERVICES         executor   scheduler   worker   controller  indexer |
  +-----------------------------------------------------------------------+
  |  ENGINE (core)    RunContext  Pebble  FlowableUtils  PluginRegistry   |
  +-----------------------------------------------------------------------+
  |  CONTRACTS (core) Queue*   Repository*   Storage*   Plugin/Task*       |  <- interfaces only
  +-----------------------------------------------------------------------+
  |  ADAPTERS         queue-jdbc   jdbc(+h2/pg/mysql)   storage-local      |  <- swappable impls
  +-----------------------------------------------------------------------+
  |  MODEL            Flow Task Execution TaskRun State Input Trigger      |
  +-----------------------------------------------------------------------+

  Rule: arrows point DOWN only. Core depends on CONTRACTS, never on ADAPTERS.
  Adapters are chosen at boot by @ConditionalOnProperty (tranto.queue.type, etc.).
```

---

## 1. Maven module dependency graph

```
                              platform (BOM)
                                   ^
                                   | (all modules import)
        model  <---  core  <---+---+--- queue-jdbc ---+
                       ^        |                     |
          plugin-core -+        +--- jdbc <--- jdbc-h2/pg/mysql
          script ------+        |
                                +--- storage-local
                                |
     executor / scheduler / worker / controller / indexer  --> core (contracts)
                                |
                          webserver --> core + (services)
                                |
                             cli --> everything (assembles a runnable app)
```

- `core` holds **interfaces + engine + model**. It never imports an adapter.
- `cli` is the only module that pulls concrete adapters together into a bootable jar.
- Swapping Postgres→Kafka = add `queue-kafka`, flip one config value. Core untouched.

---

## 2. The four core contracts (the seams)

Everything pluggable hides behind these. Simplified signatures.

```
  Queue (core/queues)
  -------------------
  interface DispatchQueue<T>        emit(T) ; QueueSubscriber<T> subscriber()
  interface KeyedDispatchQueue<T>   emit(key,T) ; subscriber(key)          // worker queues
  interface VNodeDispatchQueue<T>   emit(T) ; subscriber(vnodes)           // scheduler shards
  interface BroadcastQueue<T>       emit(T) ; subscriber()                 // kill, follow-UI
  interface QueueSubscriber<T>      subscribe(fn) ; pause() ; resume() ; close()

  Repository (core/repositories)
  ------------------------------
  interface FlowRepository          findById(tenant,ns,id,rev) ; findByNamespace ; save ; delete
  interface ExecutionRepository     findById ; find(filter,page) ; save ; ...
  interface TriggerRepository, LogRepository, MetricRepository, ...
     (all tenant-first: every read is scoped by tenantId + deleted=false)

  Storage (core/storages)
  -----------------------
  interface Storage    get(uri) ; put(uri,stream) ; list(prefix) ; delete ; move
     (uris look like  kestra://<tenant>/<namespace>/...  -- kept for compatibility)

  Plugin (model + core/plugins)
  -----------------------------
  interface Plugin              getType()                       // marker for everything loadable
  interface Task  extends Plugin  getId() ; getType()
  interface RunnableTask<O>      O run(RunContext)              // -> WORKER
  interface FlowableTask<O>      resolveNexts(...) ; resolveState(...)   // -> EXECUTOR
  interface ExecutableTask<O>    createSubflowExecutions(...)   // -> EXECUTOR (subflows)
```

---

## 3. The JDBC queue (adapter detail)

### 3.1 Table

```
  TABLE queues
  --------------------------------------------------------------
  offset       BIGINT  PK, auto-increment   -- global cursor/id
  type         VARCHAR                       -- which logical queue
  routing_key  VARCHAR  NULL                 -- worker key / vnode / NULL
  key          VARCHAR                       -- message dedup/partition key
  value        JSONB                         -- the message payload
  created      TIMESTAMP                      -- for broadcast retention
  --------------------------------------------------------------
  INDEX (type, routing_key, offset)
  INDEX (type, offset)
  PARTITION BY RANGE (created)               -- Tranto add: time partitions
```

### 3.2 Work-queue consume (dispatch / keyed / vnode) — competing consumers

```
  BEGIN TX
    SELECT offset, value FROM queues
     WHERE type = :q AND (routing_key IN :keys OR routing_key IS NULL)
     ORDER BY offset
     LIMIT :batch
     FOR UPDATE SKIP LOCKED          <-- the whole trick
    -> handler.accept(value)  for each row
    DELETE FROM queues WHERE offset IN (:handled)
  COMMIT
  -- handler throws?  -> ROLLBACK -> rows unlock -> redelivered (at-least-once)
```

### 3.3 Broadcast consume — every subscriber sees every message

```
  cursor = SELECT max(offset) ...          -- seed at subscribe time (only see NEW msgs)
  loop:
     SELECT offset,value WHERE type=:q AND offset > :cursor ORDER BY offset LIMIT :batch
     -> handler.accept(value)
     cursor = last offset                  -- no lock, no delete
  -- old rows reclaimed by dropping old partitions (Tranto) instead of DELETE sweep (Kestra)
```

### 3.4 Poll loop (Tranto improvement)

```
  Kestra:  fixed timer 25ms -> 500ms backoff
  Tranto:  Postgres LISTEN/NOTIFY wakeup  +  adaptive batch
           idle  -> block on NOTIFY (near-zero latency, zero poll load)
           busy  -> immediate re-poll while batches stay full
```

---

## 4. Executor — internals

### 4.1 Components

```
  +----------------------------------------------------------+
  |                     DefaultExecutor                       |
  |  subscribes: executionQueue, executionEventQueue,        |
  |              workerTaskResultQueue, killQueue, subflow... |
  |                                                          |
  |   +------------------+        +----------------------+   |
  |   | ExecutionState   |        |  ExecutorService     |   |
  |   |   Store (lock)   |<------>|  .process()          |   |
  |   +------------------+        |  = THE STATE MACHINE |   |
  |   | Delay / Queued / |        +----------------------+   |
  |   | SLA / Concurrency|                                   |
  |   | state stores     |        two 1s loops:              |
  |   +------------------+        - delayLoop (retry/resume) |
  |                               - slaMonitorLoop           |
  +----------------------------------------------------------+
```

### 4.2 The state machine pipeline (`ExecutorService.process`)

```
  input: (Execution, Flow)  ---- guarded by canBeProcessed() ----

   handleRestart          RESTARTED -> RUNNING
   handleEnd              all tasks terminal? -> compute final state, render outputs
   handleKilling          KILLING -> mark taskruns KILLED
   handleNext             pick next task(s) from the DAG   (FlowableUtils)
   handleAfterExecution   run 'afterExecution' hooks
   handleWorkerTasks      RunnableTask -> build WorkerTask -> queue
   handleFlowableTasks    If/Parallel/Loop/Pause/retry/subflow-wait
   handleExecutionUpdating in-executor mutations (labels, kill)
   handleExecutableTasks  Subflow -> spawn child executions

   output: updated Execution + a list of side-effects (worker tasks, delays, ...)
                                       |
                                       v
                        toExecution()  -- the single emit sink:
                          - fire flow-triggers
                          - on terminal: pop concurrency-queue, SLA purge,
                            subflow-end, TriggerExecutionTerminated
                          - emit ExecutionEvent + FollowExecutionEvent(UI)
```

### 4.3 Per-execution locking (correctness)

```
  many executors, many executions:

    executor A --lock(exec-1)-->  process exec-1   (exec-1 serialized)
    executor B --lock(exec-2)-->  process exec-2   (runs in parallel)
    executor A --lock(exec-1)?--> BLOCKED until A releases

  Tranto tweak: compute the next state OUTSIDE the lock,
                apply the delta INSIDE the lock -> shorter critical section.
```

---

## 5. Worker — internals

```
  +---------------------------------------------------------+
  |                    AbstractWorker                        |
  |                                                         |
  |  JobFetcher  ->  in-memory queue  ->  WorkerJobExecutor |
  |  (from queue      (bounded)           N virtual threads |
  |   or gRPC)                            = N consumers      |
  |                                            |            |
  |                                     WorkerTaskCallable   |
  |                                     = RunnableTask.run() |
  |                                            |            |
  |  WorkerIOSender  <-----  results/logs/metrics            |
  |  (to queue or gRPC)                                      |
  +---------------------------------------------------------+

  One task lifecycle:
    killed? -> CREATED->RUNNING -> build RunContext -> cache lookup(SHA-256)
      -> run() -> allowFailure/Warning mapping -> emit WorkerTaskResult -> cache put
```

Two wirings of the same worker:

```
  LOCAL:    JobFetcher = DirectQueueJobFetcher   (reads queue directly)
  CLUSTER:  JobFetcher = gRPC stream from controller (permit-based flow control)
```

### 5.1 Virtual-thread concurrency (Tranto's big lever)

```
  Kestra worker:  N platform threads  -> ~N tasks at once (N ~ hundreds)
  Tranto worker:  N virtual-thread consumers, each blocks freely
                  -> 100k+ concurrent tasks on a few carrier threads

  RULE (CI-enforced): no `synchronized` on the hot path (would pin a carrier).
                      use ReentrantLock; keep blocking calls JDK-21 friendly.
```

---

## 6. Scheduler — internals

```
  +-------------------------------------------------------+
  |                   DefaultScheduler                     |
  |  vNode assignment (this node owns shards [3,7,11,...]) |
  |                                                       |
  |  1s loop per thread:                                  |
  |    fetch due triggers for my vnodes                   |
  |      cron/schedule  -> build Execution -> executionQ  |
  |      polling/realtime -> WorkerTrigger -> worker      |
  |      lock trigger (unless allowConcurrent)            |
  |    apply inbound TriggerEvents (unlock on terminate)  |
  +-------------------------------------------------------+

  Tranto tweak: hashed timing wheel (O(1) fire) + work-steal across own vnodes.
```

---

## 7. Webserver — internals (Spring MVC, no WebFlux)

```
  +------------------------------------------------------------+
  |  @RestController  (thin - no business logic)               |
  |    FlowController  ExecutionController  TriggerController   |
  |    LogController   Namespace/KV/Secret  Plugin/Dashboard    |
  |         |                                                  |
  |         v          delegates to                            |
  |  @Service layer  (FlowService, ExecutionService, ...)      |
  |         |                                                  |
  |         v                                                  |
  |  Repository / Queue / Storage contracts                    |
  +------------------------------------------------------------+

  Contract kept identical to Kestra:  /api/v1/{tenant}/...
  Paging: PagedResults<T> = { results, total }
  Filters: uniform QueryFilter DSL
  UI: Kestra's Vue app served as static resources (unchanged)
```

### 7.1 SSE follow (live logs / execution) — MVC + virtual thread

```
  GET /executions/{id}/follow
     |
   controller returns an SseEmitter
     |
   ONE virtual thread:
     subscribe to FollowExecutionEvent (broadcast queue)
     loop: event -> emitter.send(event)   (blocking, cheap on a vthread)
     until execution terminal -> emitter.complete()

   100k followers = 100k virtual threads = fine.  No Reactor, no Flux.
```

---

## 8. End-to-end sequence — run a flow

```
  API/Sched     executionQ      Executor        workerJobQ      Worker      resultQ
     |             |               |                |             |            |
     |--new Exec-->|               |                |             |            |
     |             |--dispatch---->|                |             |            |
     |             |          lock+process          |             |            |
     |             |          pick task 1           |             |            |
     |             |               |--WorkerTask--->|             |            |
     |             |               |                |--dispatch-->|            |
     |             |               |                |        run() task 1      |
     |             |               |                |             |--result--->|
     |             |<----------------------------- dispatch --------------------|
     |             |          lock+process                                     |
     |             |          task 1 SUCCESS, pick task 2 ...                  |
     |             |               (repeat until no tasks)                     |
     |             |          Execution = SUCCESS                              |
     |             |--FollowExecutionEvent--> Webserver SSE --> UI             |
```

---

## 9. Sequence — a task fails and retries

```
  Worker: run() throws -> WorkerTaskResult(FAILED)
     |
  Executor: task has retry policy? 
     yes -> create ExecutionDelay(nextRetryDate) -> save in delay store
     |
  Executor.delayLoop (every 1s):
     delay expired? -> lock exec -> reset task to CREATED -> re-enter process()
     |
  -> task dispatched again  (attempt 2)
     |
  retries exhausted -> task FAILED -> execution FAILED (unless allowFailure)
```

---

## 10. Data model (persisted shapes)

```
  flows            key=uid,  value=JSON(flow),  gen cols: tenant_id, namespace, id, revision, deleted
  executions       key=id,   value=JSON(exec),  gen cols: tenant_id, namespace, flow_id, state_current, deleted
  triggers         key=uid,  value=JSON,        gen cols: tenant_id, namespace, next_execution_date, worker_id
  logs / metrics   append-only, batched by indexer
  queues           see section 3
  executordelayed / execution_queued / sla_monitor / concurrency_limit / worker_job_running
                   = executor scheduling state (survives restart)
  kv_metadata / namespace_file_metadata / settings / flow_topologies / dashboards / locks
```

Pattern everywhere: **write only `key` + `value` (JSON); DB derives the query columns.**

---

## 11. Transaction & delivery model

```
  DELIVERY:     at-least-once (rollback = redeliver)
  PROCESSING:   idempotent (immutable Execution + per-exec lock + dedup on join)
  NET EFFECT:   exactly-once-ish  -- a duplicate message re-applies the same delta safely

  TX boundaries:
    queue consume  = one TX around SELECT..FOR UPDATE / handle / DELETE
    executor step  = one TX around lock(exec) / read / apply / save + emit
    (emit-after-commit where an external side-effect must not double-fire)
```

---

## 12. Config & bean wiring (Spring)

```
  application.yml
    tranto.queue.type: postgres        -> @ConditionalOnProperty picks JdbcQueue beans
    tranto.repository.type: postgres   -> picks Postgres*Repository beans
    tranto.storage.type: local         -> picks LocalStorage bean
    tranto.server-type: STANDALONE     -> which services boot in this process
    spring.threads.virtual.enabled: true

  boot flow (cli):
    picocli command sets server-type -> Spring context starts ->
    @ConditionalOn* selects adapters -> PluginScanner loads plugins ->
    selected services start their subscribers/loops
```

---

## 13. Deployment topologies

```
  LOCAL (server local) - one JVM, H2, local FS:
    [ webserver + executor + worker + scheduler + indexer + H2 ]

  DISTRIBUTED (each scales independently, shared Postgres):
    [webserver]xN   [scheduler]xN   [executor]xN   [worker]xM   ([controller]xK for gRPC workers)
          \_______________ all coordinate only through Postgres queue + tables ______________/
```

---

## 14. Cross-cutting

```
  Security     tenant isolation in every query ; CSRF ; secret masking in logs
  Observability Micrometer metrics + OpenTelemetry traces (executor<->worker<->queue)
  Caching      Caffeine: flow-by-revision, plugin metadata, env/global vars
  Errors       typed exceptions ; poison messages surfaced (not silently dropped)
  Testing      H2RunnerTest gate ; executor correctness corpus ; N-node SKIP LOCKED contention test
```

For the map of the original Kestra internals see `01`; for the perf targets see `03`.
