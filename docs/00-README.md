# Tranto — a Kestra rebuild in Spring Boot + Java 21

Rebuilding [Kestra](../kestra) (the open-source workflow orchestrator) feature-for-feature on
a **Spring Boot 3.x + Java 21** stack, built with **Maven**, optimized for simplicity,
extensibility, loose coupling, and very high concurrency (virtual-thread-first).

**Goal: not just parity — strictly better than Kestra in every measurable respect.** Same
behavior, faster numbers. See doc 03 for the superiority scorecard.

**This is NOT a copy-paste of Kestra.** We keep the parts of Kestra's design that are genuinely
proven and we redesign the rest to be cleaner/simpler/faster/more extensible. Where we diverge,
it's written down with a rationale and a migration path — see doc 07.

**Status:** Architecture & planning complete. No application code written yet.

## Stack decisions (locked)
- **Build:** Maven multi-module reactor (root `pom.xml` + `platform` BOM). *(not Gradle)*
- **Framework:** Spring Boot 3.x, Spring Web **MVC** on virtual threads. **No WebFlux / no Reactor** — SSE via `SseEmitter`.
- **Language:** Java 21 (LTS) — virtual threads, records, sealed types, pattern matching.
- **Persistence:** jOOQ over Postgres (primary) / H2 (local+test) / MySQL — JSON-blob + generated-column pattern (kept from Kestra).
- **Queue:** JDBC "database is the broker" (`FOR UPDATE SKIP LOCKED`) by default; Kafka/Redis pluggable.
- **Templating:** Pebble (kept — keeps flow YAML 100% compatible).
- **UI:** reuse Kestra's existing Vue 3 UI unchanged against a byte-compatible REST contract.

## Documentation index
0. [`05-hld.md`](./05-hld.md) — **High-Level Design.** The simplest whole-system overview, plain
   dash diagrams only. **Read this first** if you want the big picture in 5 minutes.
0b. [`06-detailed-architecture.md`](./06-detailed-architecture.md) — **Detailed (Low-Level) Design.**
   The engineer's view: component internals, the 4 core contracts, the JDBC-queue table + consume
   loops, the executor state-machine pipeline, worker threading, SSE, sequence flows, schema,
   transactions, bean wiring. Dash diagrams throughout.
1. [`01-kestra-architecture-understanding.md`](./01-kestra-architecture-understanding.md) —
   Deep reverse-engineered map of the existing Kestra: domain model, runtime engine
   (executor/scheduler/worker/controller/indexer), the JDBC queue, persistence, API/CLI,
   plugin system. The "crown jewels" that are hard to clone. **Start here.**
2. [`02-target-architecture-spring.md`](./02-target-architecture-spring.md) — Our Spring Boot +
   Java 21 target: design principles, tech mapping, the full **Micronaut→Spring** translation
   table, the ~20-module Maven layout, and the concurrency/performance strategy.
3. [`03-beyond-kestra-performance.md`](./03-beyond-kestra-performance.md) — **How we beat Kestra:**
   the concrete, benchmark-gated improvements per subsystem and the superiority scorecard
   (targets vs the Kestra baseline).
3b. [`07-design-divergences.md`](./07-design-divergences.md) — **Where we build it differently
   (better), not copy-paste.** The intentional design divergences (D1–D11), what we
   deliberately keep unchanged, and the divergence scorecard.
3c. [`08-plugin-ecosystem-and-stability.md`](./08-plugin-ecosystem-and-stability.md) — **The
   stability contract for 500+ plugins in separate repos.** The thin SDK + "the wall" so the
   platform never changes when adding plugins; semver policy + CI tripwires.
4. [`04-build-plan.md`](./04-build-plan.md) — Phased, dependency-driven build plan (Phase 0→9),
   the parallelization map for concurrent squads, the risk register, and definition of done.

## The one thing to remember
Kestra's genius is that **one relational database is both the message bus and the state store**
(`FOR UPDATE SKIP LOCKED`), so it scales horizontally with no Kafka. We keep that exactly, and
add **Java 21 virtual threads** as our concurrency multiplier. Build the spine first —
queue + executor state machine + in-process worker (Phase 3) — because it proves the whole
architecture with one `hello_world` flow. Everything else fans out from there.
