# Tranto — System Cartography

Reverse-engineered map of the **Tranto** codebase (`D:\workflow-engine\docs\kestra\new-tranto`),
produced by the *legacy-system-cartographer* method: evidence-ranked reconstruction of *what the
system is*, *why it exists*, and *how every use case flows end-to-end*.

> **What Tranto is (one line):** an open-source-style **workflow orchestration engine** — a
> ground-up Java/Spring reimplementation of [Kestra](https://kestra.io) — that runs declarative
> YAML flows of pluggable tasks, on either an in-process engine or a JDBC-coordinated multi-node
> cluster.

## How this map was built

| Rank | Source of truth used | Where it lived |
|------|----------------------|----------------|
| 1 | **The data / schema** | `JdbcDatabase.initSchema()` — `flows`, `executions`, `queue_messages` |
| 2 | **The wires** | `QueueInterface` topics, HTTP routes, engine subscriptions |
| 3 | **The tests** | 31 test classes (executable specs) — `StandaloneEngineTest`, `DistributedEngineTest`, `RealWorldShowcaseTest`, … |
| 4 | Version control | **Not available** — the directory is not a git repo (`git.available=false`). Git archaeology could not be performed; the "how it grew" narrative is reconstructed from `docs/PROGRESS.md`/`STATUS.md` phase markers instead and flagged as such. |
| 5 | The code | all 144 Java files across 10 Maven modules |
| 6 | Comments/docs | `docs/*.md` (`STATUS.md`, `07-design-divergences.md`) — used for hypotheses & to find source/doc contradictions |

Recon inventory: **144 Java, 38 YAML, 10 XML files · 10 Maven modules · 13 entry points · 3 DB tables · 4 queue topics · 24 built-in plugins.**

## The five deliverables

1. **[System Context Map](01-system-context-map.md)** — components, stores, and the edges (incl. hidden coupling) between them.
2. **[Domain Narrative & Glossary](02-domain-narrative-glossary.md)** — why it exists, the ubiquitous language, and every core entity's lifecycle/state machine.
3. **[Use-Case Catalog](03-use-case-catalog.md)** — every entry point traced end-to-end, with flow diagrams.
4. **[Landmine Register](04-landmine-register.md)** — the dangerous truths an inheriting engineer must know.
5. **[Open Questions](05-open-questions.md)** — what could not be confirmed from source alone, and how to resolve each.

## Reading order

- **New engineer inheriting the code:** start with the Use-Case Catalog and Landmine Register.
- **Stakeholder / architect:** start with the Domain Narrative, then the System Context Map.

## Confidence

Overall **high**. The system is a clean, well-layered, well-tested rebuild (not a decayed legacy
system) with unusually honest self-documentation. The main uncertainties are (a) no git history to
confirm evolution, and (b) several interfaces have a documented "distributed/production" second
implementation that is described but **not yet built** — those gaps are the bulk of the Landmine
Register and Open Questions.
