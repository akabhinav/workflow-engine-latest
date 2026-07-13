# Open Questions — Tranto

What could not be confirmed from source alone. An honest incomplete map beats a confident wrong one.
Each row says exactly what evidence would resolve it. Most of these are *design-intent* questions
(the code is clear on *what* it does; the question is *whether that's the intended end state* or a
known interim), because there is **no git history, no issue tracker, and no original author reachable
through the repo**.

| # | Question | Why it matters | Evidence needed to resolve | Who/what can answer |
|---|----------|----------------|----------------------------|---------------------|
| 1 | Is the distributed engine meant to run **exactly one executor node**, or is multi-executor an intended (but unbuilt) goal? | Determines whether Landmine #2 (cross-node lock) is a bug or a documented constraint. | Design doc / roadmap statement on executor cardinality; a shared-lock TODO | Project owner; `docs/04-build-plan.md`, `docs/06-detailed-architecture.md` |
| 2 | Will the JDBC queue get **redelivery / SKIP LOCKED / leasing**, and is at-most-once-after-claim acceptable in the interim? | Landmine #1 is the biggest reliability gap; the answer sets its priority. | Roadmap item; the Postgres `FOR UPDATE SKIP LOCKED` design referenced in Javadoc | Project owner; `STATUS.md` line 174, `QueueInterface.java:6-9` |
| 3 | Are **KV, LocalStorage, and concurrency admission** intended to move to shared JDBC state, and by when? | They silently behave per-node today (Landmine #3); consumers need to know not to rely on cluster-wide semantics. | The "Executor state stores" roadmap line; a target schema | `STATUS.md` lines 172, 179; `docs/06` |
| 4 | Should **`kill` and `resume`** be exposed over REST/CLI? Today they're engine-API only. | Operators can't pause-approve or kill via the documented API surface (UC-09/UC-10). | Intended API surface; a controllers roadmap | `STATUS.md` line 182 (remaining controllers); product owner |
| 5 | What is the intended semantics of **`tenantId`** (always null today)? Is multi-tenancy planned, and is the DB isolation per-tenant? | Tenancy columns exist everywhere but are unenforced; affects security posture (Landmine #10). | Multi-tenancy design; auth model | `STATUS.md` line 183; `docs/02-target-architecture-spring.md` |
| 6 | Is there an intended **depth/cycle guard for flow-trigger chains** beyond "a flow can't trigger on its own executions"? | Flow A→B→A (different flows) could loop forever; `onTerminal` scans all flows each time (UC-05). | Design note on trigger-chain limits; a test asserting cycle termination | Project owner; `FlowTriggerEvaluator.java` history (unavailable) |
| 7 | On CLI **timeout**, `run()` throws instead of returning a FAILED/timed-out execution. Intended? | Affects CLI exit-code semantics and scripting around long flows (UC-01). | Intended timeout contract | `StandaloneEngine.run:100-103`; product owner |
| 8 | In **SSE follow**, is there a guaranteed-no-missed-terminal contract if the execution terminates between the initial snapshot and `subscribe`? | A client could hang waiting for a terminal event that already fired (UC-03). | A test exercising the race; or a re-check-after-subscribe in code | `ExecutionController.follow:61-83`; author |
| 9 | Are **metrics** meant to be durable/exported (Micrometer/OTel), or is the in-memory terminal-state counter the final form? | `/metrics` resets on restart and misses distributed mode (Landmine #12); dashboards depend on the answer. | Observability roadmap | `STATUS.md` lines 190, 194; `docs/03-beyond-kestra-performance.md` |
| 10 | Which `StateType` values are **aspirational vs live** (`RETRYING`, `BREAKPOINT`, `RESUBMITTED`)? | Operators/dashboards will key on states the engine never emits (Landmine #11). | A doc marking each state's status; or wiring them | `StateType.java` vs engine; author |
| 11 | Is `ServerType` (EXECUTOR/SCHEDULER/WORKER/CONTROLLER/INDEXER/WEBSERVER) the **target deployment topology**, and are CONTROLLER (gRPC) + INDEXER planned? | Sets the real production architecture vs the current JDBC-queue transport. | Deployment topology doc; the gRPC worker-controller design | `ServerType.java` docstrings; `STATUS.md` lines 177-178; `docs/06` |
| 12 | Should **`http.Request` non-2xx** optionally fail, and should credentials/URLs be policy-controlled? | Silent-success + SSRF/RCE surface (Landmines #8, #9) matter the moment the API is exposed. | Security requirements; an opt-in `failOnStatus` decision | Product/security owner |
| 13 | Is the plan to replace the hand-written **`CorePlugins.all()` manifest** fully with ServiceLoader discovery (the comment says it's temporary)? | Two discovery mechanisms coexist; drift risk (a plugin in one, not the other). | The ServiceLoader-migration roadmap item | `CorePlugins.java:31-35`; `STATUS.md` line 93 |
| 14 | **(Unresolvable from repo)** What was the actual build sequence / original core vs bolt-ons? | The "how it grew" narrative is inferred from STATUS.md phases, not commits. | `git log` — but the directory is **not a git repo**; or the original build journal | Author; `docs/PROGRESS.md` is the only proxy |

## How to close these fastest

1. **Read the three design docs not yet consulted in depth** — `docs/04-build-plan.md`,
   `docs/06-detailed-architecture.md`, `docs/07-design-divergences.md` — they likely answer #1, #3,
   #9, #11, #13 directly (this cartography deliberately reconstructed from *code*, ranking those docs
   as lower-trust sources; they are the right place to confirm *intent*).
2. **Ask the project owner** the cardinality/interim questions (#1, #2, #4, #5, #12) — these are
   product/architecture calls, not discoverable in code.
3. **Put the repo under version control** — the single highest-leverage fix for future
   archaeology (#14 and the ability to answer "when/why" for everything else).
