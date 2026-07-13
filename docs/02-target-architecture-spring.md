# Tranto — Target Architecture (Spring Boot + Java 21)

> Our rebuild of Kestra. Same features, same architecture *shape*, reimplemented on
> Spring Boot 3.x + Java 21, optimized for simplicity, extensibility, loose coupling,
> and very high concurrency. Read `01-kestra-architecture-understanding.md` first.

---

## 1. Design principles (non-negotiable)

> **This is NOT a copy-paste of Kestra.** We are building a *better* platform. We keep the
> parts of Kestra's design that are genuinely proven, and we are **free to redesign anything
> we can make cleaner, simpler, faster, or more extensible**. Where we diverge, we write down
> why (see `07-design-divergences.md`). Kestra is our reference and our baseline to beat — not
> a blueprint to trace.

1. **Keep what's proven; improve the rest.** The service split (executor / scheduler /
   worker / webserver), the "database-is-the-broker" queue, the JSON-blob persistence, and the
   plugin model are battle-tested — we keep those foundations. Everything else (module
   boundaries, APIs, the plugin SPI ergonomics, error/observability model, config) is fair game
   to redesign when it makes the platform better. Improvement beats fidelity.
2. **Keep the "database is the broker" model** (`FOR UPDATE SKIP LOCKED`). It is why Kestra
   scales without Kafka. We keep it as the default and make Kafka/Redis pluggable behind the
   same `Queue` interface for extreme scale.
3. **Loose coupling via interfaces + Spring profiles.** Every backend (queue, repository,
   storage) is an interface with swappable implementations selected by
   `@ConditionalOnProperty`. Core depends only on interfaces.
4. **Java 21 virtual threads everywhere I/O-bound.** Workers, queue subscribers, controllers,
   SSE — all on virtual threads. This is our biggest concurrency lever over the original and
   directly serves the "1 billion users" goal.
5. **Simplicity on top.** Records for data carriers, constructor injection, no clever
   inheritance towers, small focused classes. Match Kestra's own KISS rules (see its AGENTS.md).
6. **Immutable domain, functional state transitions.** `Execution`/`TaskRun`/`State` are
   immutable with `with*` copy methods — this is what makes idempotent replay safe.

---

## 2. Technology mapping

| Concern | Kestra (original) | Tranto (ours) |
|--------|-------------------|---------------|
| Language | Java 25 | **Java 21 (LTS)** — virtual threads, records, pattern matching, sealed types |
| Framework / DI | Micronaut (compile-time DI) | **Spring Boot 3.x** (Spring Framework 6) |
| Build | Gradle multi-module (77) | **Maven multi-module reactor** (consolidated ~20 — see §4) |
| Web | Micronaut HTTP + Netty | **Spring Web MVC on virtual threads** (no WebFlux). SSE via `SseEmitter`; large streams via `StreamingResponseBody` |
| Persistence | jOOQ | **jOOQ** (keep — best fit for the JSON-blob/generated-column pattern; Spring `JdbcTemplate`/`@Transactional` around it) |
| DB | H2 / Postgres / MySQL | **Postgres (primary)** + H2 (local/test) + MySQL |
| Queue | JDBC (`SKIP LOCKED`) | **JDBC (`SKIP LOCKED`)** default; Kafka/Redis pluggable |
| Serialization | Jackson (YAML/JSON/Ion) | **Jackson** (same) |
| Templating | Pebble | **Pebble** (same — keeps flow YAML 100% compatible) |
| Plugins | ServiceLoader + annotation processor | **ServiceLoader** (unchanged) + Spring for wiring |
| Boolean/validation | Jakarta Validation | **Jakarta Validation** (same) |
| Async | Project Reactor + thread pools | **Virtual threads + `CompletableFuture`** everywhere; no Reactor. Blocking code on vthreads is the model |
| Container tasks | Docker/K8s plugins | same |
| UI | Vue 3 | **reuse Kestra's Vue UI as-is** (it talks to the REST API; we keep the API contract identical) |

**Why keep jOOQ and Pebble:** they are not Micronaut-coupled, and they are load-bearing for
compatibility. Swapping them would be gratuitous risk with no upside.

**Why reuse the Vue UI:** the frontend is ~decoupled from the backend framework — it only
needs the `/api/v1/...` contract. Rebuilding it would triple the effort for zero feature gain.
We keep the REST contract byte-compatible and ship the existing UI.

---

## 3. Micronaut → Spring translation (the core porting work)

The bulk of the port is mechanical annotation translation. The table:

| Micronaut | Spring | Notes |
|-----------|--------|-------|
| `@Singleton` | `@Component` / `@Service` | constructor injection stays identical |
| `@Inject` (constructor) | (implicit) / `@Autowired` | prefer constructor, no annotation needed |
| `@Factory` + `@Bean` | `@Configuration` + `@Bean` | 1:1 |
| `@Requires(property=…, value=…)` | `@ConditionalOnProperty` | the big one — selects queue/repo/storage impls |
| `@Requires(beans=…)` | `@ConditionalOnBean` | |
| `@Replaces(X.class)` | `@Primary` / `@ConditionalOnMissingBean` | |
| `@EachBean` / `@EachProperty` | `@Bean` in a loop / `@ConfigurationProperties` binding | needs a small factory pattern |
| `@Value("${…}")` | `@Value("${…}")` | identical |
| `@ConfigurationProperties("kestra.x")` | `@ConfigurationProperties("tranto.x")` | rename prefix |
| `@Controller("/path")` | `@RestController @RequestMapping("/path")` | |
| `@Get/@Post/@Put/@Delete` | `@GetMapping/...` | |
| `@ExecuteOn(TaskExecutors.IO)` | run on virtual-thread executor (default, `spring.threads.virtual.enabled=true`) | mostly unneeded with vthreads |
| Reactor `Flux<Event<T>>` SSE | `SseEmitter` / `StreamingResponseBody` (MVC, no WebFlux) | a virtual thread blocks on the broadcast queue and writes events |
| `@Scheduled(fixedRate=…)` | `@Scheduled(fixedRate=…)` | Spring's scheduler |
| `@MicronautTest` | `@SpringBootTest` | |
| `@Introspected` | (not needed) / reflection or `@RegisterReflectionForBinding` for native | |
| picocli via `MicronautFactory` | **Spring Boot + picocli** (`picocli-spring-boot-starter` — `CommandLine` with a Spring `IFactory`) | CLI stays picocli |
| `ApplicationContext` (Micronaut) | `ApplicationContext` (Spring) | for dynamic bean lookup in plugin registry |

**Gotchas that need real thought (not just annotation swaps):**
- **Compile-time vs runtime DI.** Micronaut resolves beans at compile time; Spring at runtime
  via classpath scanning. Startup is slower but simpler to reason about. Fine for a server.
- **`@EachBean(JdbcTableConfig.class)`** — Micronaut auto-creates one repository bean per table
  config. In Spring we replace this with an explicit `@Configuration` that registers the beans
  from a table-config list (a `Map<String, JdbcTableConfig>` → `@Bean` each, or a factory
  `getRepository(tableName)`). Straightforward but must be deliberate.
- **Conditional bean graphs.** Kestra uses `@Requires(property="kestra.queue.type",
  value="postgres")` heavily. `@ConditionalOnProperty` is the direct analog; the wiring works
  identically once every impl is annotated.
- **Plugin classloader isolation** is pure JDK (`URLClassLoader`) — framework-independent,
  ports as-is.
- **jOOQ transactions** — replace Micronaut `@Transactional` with Spring `@Transactional`
  (or jOOQ's `dslContext.transaction(...)` directly, which is framework-neutral and what we
  prefer for the queue's select-process-delete atomicity).

---

## 4. Module layout (consolidated to ~20 Maven modules)

Kestra's 77 modules are finer-grained than we need. We consolidate while keeping the same
logical boundaries and the same swap-points. **Build:** a Maven multi-module reactor — a root
`pom.xml` with `<packaging>pom</packaging>` and `<modules>`, plus a `platform` module using
`<dependencyManagement>` (Maven BOM) that every child imports. Spring Boot's dependency BOM is
imported in `platform`; the Spring Boot Maven plugin builds the runnable `cli` jar.

**The plugin stability wall (critical):** the platform will host 500+ plugins in separate repos,
so plugins compile against a **thin, semver-stable SDK** (`model` + `plugin-sdk`) — never the
engine. Everything below `plugin-sdk` is private and free to change. See `08-plugin-ecosystem-and-stability.md`.

```
tranto/                       # root pom (packaging=pom, <modules>)
├── platform/                 # Maven BOM: <dependencyManagement> + versions (imported by all)
│
│   # ===== PUBLIC, SEMVER-STABLE SDK (what 500+ plugins compile against) =====
├── model/                    # @Plugin, @PluginProperty annotations + core enums (no deps)
├── plugin-sdk/               # the plugin contract: Task, RunnableTask, FlowableTask, Trigger,
│                             #   Condition, RunContext (iface), Property, Output, Input, Storage
├── processor/                # build-time ServiceLoader generation (+ future schema gen)
│   # ===== THE WALL — plugins may NOT depend on anything below this line =====
│
├── core/                     # ENGINE: implements the SDK. domain model + interfaces + primitives:
│   ├── models/               #   Flow, Task, Execution, TaskRun, State, Input, Trigger…
│   ├── queues/               #   Queue interfaces (Dispatch/Keyed/VNode/Broadcast)
│   ├── repositories/         #   Repository interfaces (tenant-first)
│   ├── storages/             #   StorageInterface
│   ├── plugins/              #   PluginRegistry, PluginScanner, PluginClassLoader
│   ├── runners/              #   RunContext, VariableRenderer(Pebble), FlowableUtils
│   └── serializers/          #   Jackson/YAML + PluginDeserializer
├── processor/                # annotation processor → META-INF/services
├── plugin-core/              # built-in io.tranto.plugin.core.* tasks (flow/log/http/kv/…)
├── script/                   # AbstractExecScript + TaskRunner + Docker runner
│
├── queue-jdbc/               # JDBC queue (SKIP LOCKED) — the crown jewel
├── jdbc/                     # jOOQ repository base + state stores + migrations base
├── jdbc-postgres/            # Postgres dialect + native fulltext
├── jdbc-h2/                  # H2 dialect (local/test)
├── jdbc-mysql/               # MySQL dialect
├── storage-local/            # local-FS StorageInterface impl
│
├── executor/                 # DefaultExecutor + ExecutorService state machine + state stores
├── scheduler/                # DefaultScheduler + TriggerScheduler (vNode sharded)
├── worker/                   # AbstractWorker + WorkerJobExecutor + SystemWorker
├── worker-controller/        # WorkerJobDispatcher (gRPC, permits) — optional for cluster
├── indexer/                  # log/metric batch indexer
│
├── webserver/                # Spring @RestController REST API + SSE + serves Vue UI
├── cli/                      # picocli entrypoint + server subcommands (local/standalone/…)
└── tests/                    # shared test harness (@SpringBootTest base, H2RunnerTest)
```

Optional queue backends (`queue-kafka`, `queue-redis`) plug in later behind the same interface.

---

## 5. Concurrency & performance strategy ("1 billion users")

The original already scales horizontally; our levers to push further:

1. **Virtual threads (Java 21)** — every worker job consumer, every queue subscriber poll,
   every blocking controller handler runs on a virtual thread. Millions of concurrent
   blocking tasks with a handful of carrier threads. This is the headline win.
2. **Horizontal scale, unchanged.** Every service (executor/scheduler/worker/webserver) is
   stateless and scales by adding instances; the DB queue's `SKIP LOCKED` distributes work
   with no coordinator. vNode sharding scales the scheduler.
3. **Read the DB less, cache more.** Flow definitions, plugin metadata, and env/global vars
   are hot and near-immutable — cache aggressively (Caffeine), invalidate on flow revision.
4. **Batch everything on the hot path.** Queue polls, worker-result joins, log/metric writes
   are already batched; keep it. Indexer keeps high-volume writes off the execution path.
5. **Partition the big tables.** `executions`, `logs`, `metrics`, `queues` get time/tenant
   partitioning in Postgres from day one (Kestra retrofits this; we design for it).
6. **Connection-pool discipline.** HikariCP sized per service role; the queue poller and the
   executor get separate pools so a queue storm can't starve execution.
7. **Backpressure preserved.** The permit-based worker flow control (never drop a job) is
   kept exactly.
8. **Idempotency preserved.** At-least-once delivery + per-execution locking + immutable
   state joins = safe replay. Non-negotiable; it's what makes the whole thing correct at scale.

---

## 6. What we deliberately keep identical (compatibility contract)

- **Flow YAML syntax** — 100% compatible (same Jackson model + Pebble). Existing Kestra flows
  must run unchanged.
- **REST API `/api/v1/{tenant}/...` contract** — byte-compatible, so the Vue UI and Terraform
  provider work unchanged.
- **State machine semantics** — same `State.Type` values, same transitions, same retry/pause/
  loop/subflow behavior.
- **Plugin SPI** — same `Plugin`/`Task`/`RunnableTask`/`FlowableTask` contracts (renamed
  package `io.tranto.*` but structurally identical), so plugins are a mechanical port.

**These are pragmatic defaults, not a cage.** Compatibility buys us the free Vue UI and lets
existing Kestra flows run — huge leverage. So we keep it *by default*. But where a divergence
makes the platform clearly better (a cleaner plugin SPI, an evolvable API envelope, a stronger
state model), **we are free to take it** — with a documented rationale and a migration path.
Compatibility is a benefit we protect, not a constraint we obey blindly. See
`07-design-divergences.md` for exactly where and why we deviate.

The baseline intentional differences: framework (Spring), Java version (21), package root
(`io.tranto`), Maven build, virtual-thread-first concurrency — plus the deliberate design
improvements catalogued in doc 07.
