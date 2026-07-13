# Tranto — Design Divergences from Kestra

> Where we intentionally build it **differently and better** than Kestra — beyond the raw
> performance targets in doc 03. This is the "we are not copy-pasting" charter.
> Each divergence: what Kestra does, what we do instead, why it's better, and the cost.
> Dash diagrams only.

---

## Ground rules for diverging

```
  WE DIVERGE WHEN it makes the platform  simpler  |  cleaner  |  faster  |  more extensible
  WE KEEP KESTRA'S design when it is genuinely proven and we can't clearly beat it
  EVERY divergence has:  a reason  +  a migration path  +  it must not break correctness
  COMPATIBILITY (YAML + UI) is protected by DEFAULT, extended deliberately, broken only with a plan
```

Kestra is 6+ years of hard-won lessons. We respect that. We diverge with evidence, not ego.

---

## D1 — Fewer, cleaner modules  (77 -> ~20)

```
  Kestra:  77 Gradle submodules (fine-grained, lots of one-class modules)
  Tranto:  ~20 Maven modules along real seams (model/core/adapters/services/apps)

  WHY:  faster builds, easier navigation, less ceremony, clearer ownership
  COST: slightly coarser dependency isolation -> mitigated by enforced package rules
        (ArchUnit tests: core must not import adapters)
```

---

## D2 — Two queue shapes, not five

```
  Kestra:  Dispatch + KeyedDispatch + VNodeDispatch + Broadcast + a legacy QueueInterface
  Tranto:  WorkQueue<T>  (routing key optional; vnode is just a routing-key strategy)
           BroadcastQueue<T>

  WHY:  vnode and keyed dispatch are the SAME mechanism with different key functions.
        Collapsing them removes a whole class hierarchy and a legacy interface.
  COST: none externally; internal simplification only.
```

```
   WorkQueue<T>
     .emit(msg)                 // key derived from msg (KeyFn)
     .subscriber(keyFilter?)    // null = plain competing-consumer
                                // vnode  = keyFilter over hash shards
                                // worker = keyFilter over worker-queue id
```

---

## D3 — Typed, self-describing plugin SPI

```
  Kestra:  @PluginProperty + Swagger @Schema, JSON schema assembled at runtime; outputs are
           loosely-typed Output marker; lots of reflection.
  Tranto:  records + sealed types for task inputs/outputs; schema generated at BUILD time by
           our annotation processor (same one that writes ServiceLoader files).

  WHY:  compile-time schema = faster startup, no runtime reflection cost, errors caught early,
        better IDE support for plugin authors.
  COST: plugin authors use records (they already should) -> better, not worse.
  KEEP: the ServiceLoader discovery + classloader isolation (proven).
```

---

## D4 — Explicit idempotency keys (make "exactly-once-ish" first-class)

```
  Kestra:  idempotency emerges from immutable Execution + per-exec lock + join dedup.
  Tranto:  same, PLUS every queue message carries an explicit idempotencyKey, and every
           state-applying handler records "applied keys" -> duplicate delivery is a no-op by
           construction, not by careful coincidence.

  WHY:  turns a subtle invariant into an enforced one; makes replay provably safe; simplifies
        reasoning for every new handler author.
  COST: one extra column + a small applied-keys check. Cheap. Worth it.
```

```
   message {  idempotencyKey  payload  }
   handler:  if seen(key) -> skip ;  else apply + mark(key)   (in the SAME tx)
```

---

## D5 — Evolvable API envelope from day one

```
  Kestra:  some endpoints return bare arrays / shapes that are hard to evolve compatibly.
  Tranto:  every response is an object envelope:  { data, meta, errors }
           lists always paginated:  { data:[...], meta:{ page,size,total } }

  WHY:  never repaint yourself into a corner; adding a field never breaks a client.
  COMPAT: we still serve the exact Kestra shapes under /api/v1/ (UI reuse); the envelope is the
          default for /api/v2/ and all new endpoints. Both coexist.
```

---

## D6 — Observability & errors as first-class (not bolted on)

```
  Kestra:  metrics/tracing present but added over time; error handling per-site.
  Tranto:  a single typed error model (sealed TrantoError hierarchy) + automatic
           problem-detail (RFC 9457) responses + tracing spans threaded through
           executor<->queue<->worker by default.

  WHY:  every failure is diagnosable the same way; "why is this slow / stuck" is answerable
        from traces without code spelunking. This is a product feature at scale.
  COST: upfront discipline. Pays back immediately in ops.
```

---

## D7 — Config: one clear model, sane defaults, fail-fast

```
  Kestra:  large, deeply-nested application.yml; many knobs; some overlap.
  Tranto:  typed @ConfigurationProperties records with validation; boot FAILS FAST on a bad/
           ambiguous config instead of half-starting; a `tranto config explain` command prints
           the effective, resolved config with sources.

  WHY:  misconfiguration is the #1 ops pain; make it loud and self-explaining.
  COST: none; strictly better DX.
```

---

## D8 — Pluggable queue backend, honestly abstracted

```
  Kestra:  JDBC queue is the OSS path; other backends are EE.
  Tranto:  WorkQueue/BroadcastQueue are clean interfaces; JDBC is default, and queue-kafka /
           queue-redis are first-class OSS adapters selected by one config value.

  WHY:  extreme-scale users can opt into a broker WITHOUT us re-architecting; the interface was
        designed for it from the start (not retrofitted).
  COST: we must keep the interface backend-agnostic (no leaky JDBC assumptions) -> good hygiene.
```

---

## D9 — Storage: streaming-first, tenancy enforced in the type

```
  Kestra:  StorageInterface with many methods; tenancy passed as params (easy to forget).
  Tranto:  Storage scoped by a TenantContext value object; every uri is minted through a
           builder that CANNOT produce a cross-tenant path; all large I/O is streaming by
           contract (InputStream/OutputStream, never full-buffer).

  WHY:  make cross-tenant leakage impossible by construction; make OOM-on-big-file impossible.
  COST: slightly stricter API. That strictness is the point.
```

---

## D10 — Worker: virtual-thread-native design (not thread-pool-native)

```
  Kestra:  worker sized by thread-pool count (worker-thread = 8x CPU); backpressure via pool.
  Tranto:  worker is virtual-thread-native; concurrency bounded by a SEMAPHORE (memory-aware),
           not a thread count. "How many tasks" is decoupled from "how many threads".

  WHY:  a node runs as many tasks as its MEMORY allows, not its thread count -> far higher
        density, simpler tuning (one number: max in-flight, defaulted from heap).
  COST: must audit for thread-pinning (CI-enforced). Already required for Java 21.
```

---

## D11 — Thin, semver-stable Plugin SDK (not the whole engine)

```
  Kestra:  plugins compileOnly -> io.kestra:core  (the ENTIRE engine as compile surface)
  Tranto:  plugins provided    -> tranto-plugin-sdk + tranto-model  (interfaces + value types only)

  WHY:  the platform will host 500+ plugins in separate repos. Compiling them against the whole
        engine means any engine change can break them. A tiny stable SDK behind a hard "wall"
        (plugins can't even see engine classes) lets the engine evolve freely while every plugin
        keeps building & running. CI tripwires: japicmp API-diff + ArchUnit (no engine import).
  COST: every class a plugin needs must be deliberately promoted to the SDK as an interface.
        That discipline IS the feature — a reviewed, minimal public API.
  PROOF: our own plugin-core built-ins compile against the SDK only, exactly like external plugins.
```
See `08-plugin-ecosystem-and-stability.md` for the full contract.

---

## What we deliberately DON'T change (proven — leave it alone)

```
  KEEP: DB-as-broker with FOR UPDATE SKIP LOCKED       (the core scaling insight)
  KEEP: immutable Execution/TaskRun/State + replay      (correctness foundation)
  KEEP: RunnableTask vs FlowableTask split              (clean worker/executor boundary)
  KEEP: JSON-blob + generated-column persistence         (perfect fit for evolving schemas)
  KEEP: Pebble templating + flow YAML                    (compatibility + it's good)
  KEEP: ServiceLoader plugin discovery + classloader isolation
  KEEP: per-execution locking for serialization
```

Changing any of these would add risk without clear payoff. Discipline is knowing the difference.

---

## Divergence scorecard (tracked as we build)

```
  ID   Area              Better because           Compat impact        Status
  --   ----              --------------           -------------        ------
  D1   modules           faster build/nav         none                 planned
  D2   queue shapes      less code                none                 planned
  D3   plugin SPI        compile-time schema      plugin authors only  planned
  D4   idempotency keys  provable replay safety   none (internal)      planned
  D5   API envelope      evolvable                additive (v2)        planned
  D6   errors/observ.    diagnosable at scale     additive             planned
  D7   config            fail-fast + explain      none                 planned
  D8   queue backends    opt-in broker            none                 planned
  D9   storage tenancy   leak-proof by type       internal             planned
  D10  worker vthreads   memory-bound density     none                 planned
  D11  thin plugin SDK   engine evolves freely    plugins: SDK only    planned
```

Each moves to `built` + a benchmark/test link as we implement it. New divergences get an ID here
before they land — no silent redesigns.
