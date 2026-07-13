# Tranto — Beyond Kestra: How We Build It Better

> Parity is the floor, not the ceiling. This doc defines where and how Tranto **outperforms
> Kestra** in every dimension. Every item here is a concrete, measurable improvement over the
> original — not a vibe. Each carries a target and how we prove it (benchmark/gate).
> Read after `01` (what Kestra does) and `02` (our stack).

---

## Prime directive

**Same behavior, strictly better numbers.** A flow that runs on Kestra runs identically on
Tranto — but faster, at higher throughput, with lower latency, lower memory, and higher
concurrency. We never trade correctness for speed (at-least-once + idempotency stay sacred),
and we never trade simplicity for a micro-optimization that isn't proven by a benchmark.

Everything is measured. `jmh-benchmarks` + a load-test harness are Phase-0 citizens, not an
afterthought, so every claim below has a red/green gate.

---

## 1. Concurrency — virtual threads as the multiplier

**Kestra:** platform threads + Reactor + bounded pools. Each blocked worker task or queue
poll pins a real OS thread; concurrency is capped by pool sizes (`worker-thread` default 8×CPU).

**Tranto:** Java 21 **virtual threads** for every blocking, I/O-bound path — worker job
consumers, queue subscribers, controller handlers, SSE emitters, subflow waits.
- A single worker node runs **hundreds of thousands** of concurrent blocking tasks on a
  handful of carrier threads instead of ~hundreds.
- No thread-pool tuning knobs to get wrong; the runtime schedules.
- **Target:** ≥10× concurrent in-flight tasks per worker node at equal memory vs Kestra.
- **Gate:** a benchmark that ramps concurrent sleeping tasks until latency degrades; compare
  the knee point against Kestra on identical hardware.

**Discipline:** virtual threads must never pin. Audit every `synchronized` block on the hot
path (replace with `ReentrantLock`), and keep all blocking calls JDK-21-friendly. This is a
CI check, not a hope.

---

## 2. The JDBC queue — same design, faster engine

The `FOR UPDATE SKIP LOCKED` model is kept (it's why Kestra needs no Kafka). We make the
*implementation* faster:

| Improvement | How | Beats Kestra because |
|-------------|-----|----------------------|
| **Smarter adaptive polling** | Per-queue latency-aware backoff + notification-driven wakeups (Postgres `LISTEN/NOTIFY`) so idle consumers wake on insert instead of polling on a timer | Kestra's 25–500ms timer floor adds latency when idle; `NOTIFY` cuts idle-→-dispatch latency to sub-ms without polling load |
| **Native table partitioning from day one** | `queues`, `executions`, `logs`, `metrics` partitioned by time (+ tenant hash) in Postgres | Kestra retrofits partitioning; index/scan cost stays flat as volume grows |
| **Larger, self-tuning batch sizes** | Batch poll size auto-scales with observed throughput | fewer round-trips per message at peak |
| **Aggressive dispatch-row reclaim** | dispatch rows are deleted in-txn (as Kestra) but broadcast retention runs as a partition-drop, not a `DELETE` sweep | O(1) partition drop vs O(n) delete + vacuum pressure |
| **Prepared-statement + pipeline reuse** | one hot prepared statement per queue per connection | lower per-message CPU |

- **Target:** ≥2× queue throughput (msgs/s/node) and ≥50% lower p99 dispatch latency under load.
- **Gate:** JMH + a multi-node contention benchmark counting msgs/s and measuring p50/p99/p999.

---

## 3. Executor — a leaner, cache-hot state machine

**Kestra:** `ExecutorService.process()` re-derives a lot per cycle; flow lookups hit the repo.

**Tranto improvements:**
- **Flow-definition cache (Caffeine), revision-keyed** — a flow revision is immutable, so cache
  it forever and invalidate on new revision. Zero repo hits for the hot definition.
- **Compiled flow plan** — parse the flow's task graph once per revision into a pre-resolved
  plan (task index, edges, flowable structure) instead of walking the model every cycle.
- **Reduced lock scope** — keep per-execution locking (correctness) but shorten the critical
  section: compute next-state *outside* the lock, apply the delta *inside* it.
- **Zero-copy state joins** — structural sharing on the immutable `Execution` (persistent data
  structures) so `withTaskRun` doesn't deep-copy the whole task-run list.
- **Target:** ≥2× executions/s throughput per executor node; ≥40% lower CPU per state transition.
- **Gate:** a benchmark driving N concurrent executions of a fixed flow shape; measure
  executions/s and CPU-per-transition vs Kestra.

---

## 4. Persistence — same pattern, tighter execution

- **jOOQ + `JdbcTemplate`** with **HikariCP pools sized per service role**; the queue poller and
  the executor get separate pools so a queue storm can't starve execution (Kestra shares more).
- **Generated columns + covering indexes** designed up front for the exact query shapes the UI
  and executor use (Kestra evolved these incrementally, leaving gaps).
- **Read replicas** for the webserver's list/search endpoints; writes to primary. Interface stays
  the same; routing is a datasource concern.
- **Batch writes** for logs/metrics via the indexer stay, but with `COPY`-style bulk insert on
  Postgres instead of multi-row `INSERT`.
- **Target:** ≥30% lower write amplification; list/search p99 under half of Kestra's at 10× data.
- **Gate:** load a 10×-scale dataset; run the UI's top-10 queries; compare p99.

---

## 5. Memory & startup footprint

- **Records + sealed types** for data carriers → less object header overhead than Lombok POJOs
  where immutability allows.
- **Optional GraalVM native-image** build for the CLI and stateless services (webserver,
  scheduler) → sub-100ms startup, lower RSS, better for autoscaling/K8s scale-to-zero. (Spring
  Boot AOT makes this viable; Micronaut has it too, but we get it without Micronaut's constraints.)
- **Off-heap / streaming for large payloads** — task I/O and file previews stream through
  `StreamingResponseBody`, never fully buffered.
- **Target:** ≥30% lower steady-state RSS per service; native CLI cold-start < 100ms.
- **Gate:** measure RSS under fixed load; measure native image cold-start.

---

## 6. Scheduler — finer sharding, tighter timing

- Keep vNode sharding; add **work-stealing across vNodes** on the same node so a hot shard
  doesn't idle its siblings.
- **Monotonic-clock, drift-corrected** cron evaluation with a lookahead wheel (hashed timing
  wheel) instead of a 1s scan of all due triggers → O(1) fire dispatch.
- **Target:** support ≥10× trigger count per scheduler node at the same tick accuracy.
- **Gate:** register N cron triggers; measure fire-time accuracy (jitter) and CPU vs Kestra.

---

## 7. Web/API — MVC on virtual threads, no reactive tax

- Spring Web **MVC on virtual threads** (no WebFlux). We get async scalability *without* the
  reactive programming-model complexity — simpler code, easier to keep correct, and no
  Reactor overhead on the hot path.
- **SSE via `SseEmitter`**: one virtual thread per follower blocks on the broadcast queue — cheap
  at 100k+ concurrent followers.
- **HTTP response caching + ETag** on immutable resources (flow revisions, plugin schemas).
- **Target:** ≥100k concurrent SSE followers per webserver node; API p99 ≤ Kestra's at 5× RPS.
- **Gate:** SSE fan-out load test; API throughput ramp.

---

## 8. Observability that makes "better" provable

You can't claim "better" without measuring it, so observability is a feature:
- Micrometer metrics on every hot path (queue depth, dispatch latency, transitions/s,
  vthread pinning events), OpenTelemetry tracing across executor↔worker↔queue.
- A **continuous benchmark suite** (JMH + Gatling/k6 load tests) run in CI against both Tranto
  and a pinned Kestra baseline, publishing a comparison dashboard. A regression that makes us
  slower than the last release (or slower than Kestra) **fails the build**.

---

## 9. Superiority scorecard (the definition of "better")

Every dimension has a target vs the Kestra baseline. Ship gate = all green.

| Dimension | Metric | Target vs Kestra |
|-----------|--------|------------------|
| Concurrency | in-flight tasks / worker node | **≥ 10×** |
| Queue throughput | msgs/s / node | **≥ 2×** |
| Queue latency | p99 enqueue→dispatch | **≤ 50%** |
| Executor throughput | executions/s / node | **≥ 2×** |
| Executor CPU | CPU / state transition | **≤ 60%** |
| API latency | p99 at 5× RPS | **≤ 100%** (no worse at 5× load) |
| SSE fan-out | concurrent followers / node | **≥ 100k** |
| Memory | steady-state RSS / service | **≤ 70%** |
| Cold start | CLI / stateless service | **< 100ms (native)** |
| Scheduler scale | triggers / node at same jitter | **≥ 10×** |

---

## 10. Non-negotiables (what "better" must NOT break)

1. **Correctness first.** At-least-once delivery + per-execution locking + idempotent replay are
   sacred. No optimization may weaken them. Every perf change ships with the executor
   correctness corpus still green.
2. **Simplicity on top.** No optimization enters without a benchmark proving it matters. We reject
   clever code that isn't paying for itself — the "gas factory" rule from Kestra's own guidelines.
3. **Compatibility.** Flow YAML and the REST contract stay identical; performance work is
   invisible to users except as speed.
4. **Every claim is gated.** No "better" ships as an assertion; it ships as a passing benchmark
   against the pinned Kestra baseline.
