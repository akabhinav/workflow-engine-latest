# Tranto — High-Level Design (HLD)

> The simplest possible overview of the whole system. Read this first.
> All diagrams are plain dash/ASCII. Detail lives in docs 01–04.

---

## 1. What it is (one line)

**You write a workflow in YAML. Tranto runs it — on schedule or on events — reliably and fast.**

```
  YAML flow  ---->  Tranto  ---->  tasks run, in order, with retries & logs
```

---

## 2. The big picture

Five small services share one database. That's the whole system.

```
                        +---------------------+
        user / API ---> |     WEBSERVER       |  REST API + UI
                        |  (build & watch)    |
                        +----------+----------+
                                   |
        +-------------+     +------ v ------+     +---------------+
        |  SCHEDULER  | --> |   DATABASE    | <-- |   EXECUTOR    |
        | (cron/      |     | queue + state |     | (the brain,   |
        |  triggers)  | <-- | (one Postgres)|     |  decides next)|
        +-------------+     +------ ^ ------+     +-------+-------+
                                   |                      |
                                   |              +-------v-------+
                                   +------------- |    WORKER     |
                                                  | (runs a task) |
                                                  +---------------+
```

- **Webserver** — REST API + serves the UI. Where you create flows and watch runs.
- **Scheduler** — fires flows on a cron/trigger.
- **Executor** — the brain. Looks at a running flow and decides the next task.
- **Worker** — the muscle. Actually runs one task (a script, an HTTP call, etc.).
- **Database** — the shared backbone: it is **both the message queue AND the storage**.

Everything talks **through the database**. No service calls another directly. That is the
single most important idea in the whole design.

---

## 3. Why "the database is the queue" matters

Instead of adding Kafka/RabbitMQ, Tranto uses one DB table as the message bus.

```
   producer                 queues table                 consumers
   --------                 ------------                 ---------
   INSERT a row  ------->  | id | msg | ... |  <------  SELECT ... FOR UPDATE
                           | 1  | ... |     |            SKIP LOCKED
                           | 2  | ... |     |           (each node grabs a
                           | 3  | ... |     |            different row)
                           +----------------+
```

- Many worker/executor nodes poll the same table.
- `SKIP LOCKED` means each node grabs **different** rows — no two nodes do the same work.
- If a node crashes mid-task, its rows unlock and another node picks them up. **Nothing is lost.**

**Result:** add more nodes = more throughput. No broker to run. Simple and scalable.

---

## 4. How one flow runs (the core loop)

Follow a `hello_world` flow from trigger to done:

```
  1. START      Scheduler (or API) creates an Execution   ---> [DB queue]
                     |
  2. DECIDE     Executor reads it, picks the first task   ---> [DB queue: "run task"]
                     |
  3. RUN        Worker takes the task, runs it            ---> [DB queue: "task done"]
                     |
  4. DECIDE     Executor reads the result, picks next task
                     |
                (repeat 2-3-4 until no tasks left)
                     |
  5. DONE       Executor marks Execution = SUCCESS        ---> [DB: live update -> UI]
```

The Executor and Worker just **ping-pong through the queue** until the flow is finished.
The Executor never runs a task; the Worker never decides what's next. Clean split.

---

## 5. The building blocks (what's inside)

```
   FLOW (your YAML)
     |
     +-- tasks     : the steps to run        (Log, HttpRequest, RunScript, ...)
     +-- triggers  : when to start           (cron, webhook, another flow)
     +-- inputs    : parameters you pass in
     +-- outputs   : results it produces

   EXECUTION (one run of a flow)
     |
     +-- taskRuns  : one per task, each with a STATE
     +-- state     : CREATED -> RUNNING -> SUCCESS / FAILED / ...
```

**Task types** — two kinds, and this split drives everything:

```
   +------------------+        +-------------------------+
   |  RunnableTask    |        |   FlowableTask          |
   |  "do work"       |        |   "control the flow"    |
   |  runs on WORKER  |        |   runs on EXECUTOR      |
   |  e.g. HTTP, shell|        |   e.g. If, Parallel,    |
   |                  |        |        Switch, Loop     |
   +------------------+        +-------------------------+
```

**Plugins** — every task is a plugin. Drop a JAR in the plugins folder → new task types
appear. Discovered automatically at startup. This is how Tranto stays extensible.

---

## 6. Where data lives

```
   DATABASE (Postgres)                     FILE STORAGE (local / S3)
   ------------------                      -------------------------
   queues     : the message bus            task inputs/outputs (big files)
   flows      : your workflow definitions  logs (large)
   executions : every run + its state      uploaded files
   triggers   : schedule state             namespace files
   logs/metrics                            (referenced by "kestra://" URIs)
```

Small structured data → DB. Big blobs/files → file storage. The DB just holds a pointer.

---

## 7. Two ways to deploy

**Local / demo — everything in one process:**

```
   +---------------------------------------------+
   |            single JVM (server local)        |
   |  webserver + executor + worker + scheduler  |
   |            + embedded H2 database            |
   +---------------------------------------------+
   Just run it. Zero setup. Great for dev.
```

**Production — each service scales on its own:**

```
   [webserver] x2 ---+
   [scheduler] x2 ---+---> [ Postgres ] <---+--- [executor] x3
                     |                      |
                     +------ shared --------+--- [worker]   x20  (scale these for more work)
```

Same code, same database design. You just run more copies of whichever service is the
bottleneck. Workers are usually what you scale.

---

## 8. Key design decisions (and why)

```
   DECISION                        WHY
   --------                        ---
   DB as the queue (SKIP LOCKED)   Scale without Kafka. Simpler ops.
   Split services via queue only   Loose coupling. Scale each part alone.
   Immutable Execution + replay    Safe to retry. Never corrupts state.
   Java 21 virtual threads         1 worker node handles 100k+ tasks at once.
   Spring MVC (no WebFlux)         Simple blocking code, still massively concurrent.
   Plugins via ServiceLoader       Add task types without touching the core.
   Reuse Kestra's YAML + UI        100% compatible; no wasted rebuild effort.
```

---

## 9. One-glance summary

```
   WRITE          RUN                         WATCH
   -----          ---                         -----
   YAML flow  ->  Scheduler starts it     ->  UI shows live logs
                  Executor decides steps       & task states
                  Workers run the steps
                  (all via the DB queue)

   Better than Kestra: same behavior, faster (virtual threads, caching,
   partitioning, LISTEN/NOTIFY) — every gain proven by a benchmark.
```

For deeper detail: `01` (Kestra internals) · `02` (our Spring stack) · `03` (performance) · `04` (build plan).
