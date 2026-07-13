# Tranto — Operations Runbook

Practical, copy-paste operations for building, running, operating, and troubleshooting the platform.
Everything here has been verified against the current build (10 modules, 53 tests green).

- Architecture & status: [`00-README.md`](./00-README.md), [`STATUS.md`](./STATUS.md)
- Custom plugins: [`09-custom-plugin.md`](./09-custom-plugin.md)

---

## 1. Prerequisites

| Requirement | Version | Check |
|---|---|---|
| JDK | 21 (virtual threads) | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| OS | Linux / macOS / Windows | — |

> **Windows/Git-Bash caveat:** `java` may not be on the shell PATH even though Maven works. Use the
> JDK's absolute path, e.g. `"$JAVA_HOME/bin/java" -jar …` (here `JAVA_HOME=C:\Program Files\Java\jdk-21`).

---

## 2. Build

```bash
mvn clean install
```

- Produces the CLI (`cli/target/tranto.jar`) and server (`webserver/target/tranto-server.jar`).
- Runs all 53 tests.

> **Always use `clean`.** The annotation processor regenerates the plugin manifest
> (`META-INF/services/io.tranto.core.models.Plugin`) only on a *clean* compile. A plain
> `mvn install` on an already-compiled tree can leave a stale manifest → "Unknown plugin type".

Fast inner loop:
```bash
mvn install -DskipTests            # skip tests
mvn install -pl core -am           # one module + its deps
mvn test -pl cli -Dtest=RealWorldShowcaseTest   # one test class
```

---

## 3. Run a flow (CLI)

```bash
java -jar cli/target/tranto.jar run <flow.yaml> [options]
```

| Option | Purpose | Example |
|---|---|---|
| `-i, --input k=v` | pass a flow input (repeatable) | `-i email=ada@x.com -i year=2026` |
| `--register f.yaml` | pre-register a dependency flow (subflows) | `--register child.yaml` |
| `--plugins <dir>` | load external plugin JARs from a directory | `--plugins ./plugins` |
| `-t, --timeout <sec>` | max wait (default 60) | `-t 120` |

Exit code: **0** if the execution ends `SUCCESS`, **1** otherwise (usable in CI/cron).

Examples:
```bash
java -jar cli/target/tranto.jar run examples/hello_world.yaml
java -jar cli/target/tranto.jar run examples/real-world/16-ci-build.yaml
java -jar cli/target/tranto.jar run examples/real-world/12-monthly-report.yaml -i year=2027
java -jar cli/target/tranto.jar run examples/complex-db/02-etl-dedupe-aggregate.yaml   # self-verifying
```

---

## 4. Run the server (REST + SSE)

```bash
java -jar webserver/target/tranto-server.jar                      # port 8080
java -jar webserver/target/tranto-server.jar --server.port=8085   # if 8080 is busy
```

Endpoints (`{ns}` = namespace):

| Method & path | Purpose |
|---|---|
| `POST /api/v1/flows` (body: YAML, `Content-Type: text/plain`) | create/replace a flow |
| `GET  /api/v1/flows/{ns}/{id}` | read a flow |
| `POST /api/v1/executions/{ns}/{id}` | trigger an execution (non-blocking) |
| `GET  /api/v1/executions/{id}` | poll execution state |
| `GET  /api/v1/executions/{id}/follow` | live-follow via Server-Sent Events |

End-to-end with curl:
```bash
# 1) register a flow
curl -s -X POST localhost:8085/api/v1/flows \
  -H 'Content-Type: text/plain' --data-binary @examples/hello_world.yaml

# 2) trigger it (returns an execution id)
curl -s -X POST localhost:8085/api/v1/executions/dev/hello_world

# 3) poll status
curl -s localhost:8085/api/v1/executions/<execution-id>

# 4) live-follow (streams events until terminal)
curl -N localhost:8085/api/v1/executions/<execution-id>/follow
```

---

## 5. Common operations

### Schedule a flow (cron)
Add a `Schedule` trigger; the scheduler creates executions when due (checks every second):
```yaml
triggers:
  - id: nightly
    type: io.tranto.plugin.core.trigger.Schedule
    cron: "0 2 * * *"        # daily 02:00
    timezone: UTC
```

### Pause / resume / kill (programmatic)
The engine exposes lifecycle control (used by the API layer / tests):
```java
engine.submit(flow, inputs);      // start async
engine.resume(executionId);       // continue a PAUSED execution
engine.kill(executionId);         // cooperatively terminate → KILLED
```

### Limit concurrency (one run at a time)
```yaml
concurrency:
  limit: 1
  behavior: QUEUE      # or CANCEL / FAIL
```

### Talk to a database
```yaml
- id: load
  type: io.tranto.plugin.core.jdbc.Execute
  url: "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1"   # or jdbc:postgresql://…
  sql: "UPDATE orders SET status='DONE' WHERE status='NEW'"
- id: count
  type: io.tranto.plugin.core.jdbc.Query
  url: "jdbc:h2:mem:app;DB_CLOSE_DELAY=-1"
  sql: "SELECT COUNT(*) FROM orders WHERE status='DONE'"   # → {{ outputs.count.firstValue }}
```
> For non-H2 databases, put the JDBC driver JAR on the classpath. H2 is bundled.

### Add a custom plugin
See [`09-custom-plugin.md`](./09-custom-plugin.md). Short version:
```bash
cp my-plugin/target/*.jar ./plugins/
java -jar cli/target/tranto.jar run my-flow.yaml --plugins ./plugins
```

---

## 6. Verify / health checks

```bash
mvn clean install                                        # full suite (53 tests)
mvn test -pl cli -Dtest=RealWorldShowcaseTest            # 20 real-world workflows
mvn test -pl cli -Dtest=ComplexDbWorkflowsTest           # 10 complex DB pipelines (verified in-DB)
java -jar cli/target/tranto.jar run examples/hello_world.yaml   # smoke test → SUCCESS, exit 0
```

Confirm the plugin manifest was generated (should list 23 built-ins):
```bash
cat plugin-core/target/classes/META-INF/services/io.tranto.core.models.Plugin
```

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unknown plugin type 'io.tranto…'` | stale/absent ServiceLoader manifest | `mvn clean install` (clean forces the processor to re-run) |
| `command not found` / exit 127 running `java` | `java` not on PATH | use `"$JAVA_HOME/bin/java"` |
| Server won't start / `Port 8080 already in use` | port taken | `--server.port=8085` |
| `Wrong user name or password [28000]` (H2) | mismatched JDBC creds | omit `username/password` (defaults to `sa`/empty) or set both consistently |
| `Syntax error … expected identifier` on a column | reserved word (`value`, `hour`, `user`, …) | rename the column (e.g. `data`, `hr`) |
| Execution stuck `PAUSED` | a `Pause` task is waiting | call `engine.resume(id)` (or the resume API) |
| Execution `QUEUED` and not starting | concurrency limit reached | wait for the running one to finish, or raise `concurrency.limit` |
| Flow with a subflow fails: flow not found | child not registered | CLI: `--register child.yaml`; API: `POST /flows` the child first |
| Custom plugin not found under `--plugins` | JAR missing its manifest | ensure the plugin module runs `tranto-processor` (see plugin how-to) |
| Retrying task ends `FAILED` | retries exhausted (by design) | expected — inspect the task error; raise `retry.maxAttempts` if appropriate |

Read logs: tasks log via SLF4J to the console (`flow` logger). Increase verbosity with a
`logback.xml`/`-Dlogging.level.io.tranto=DEBUG` on the server.

---

## 8. Module map (where things live)

| Module | Role |
|---|---|
| `platform` | dependency BOM (version management) |
| `model` | annotations + enums (`StateType`, …) |
| `plugin-sdk` | **the stable contract** plugins compile against (Task, RunContext, triggers, storage, kv) |
| `processor` | build-time annotation processor → generates `META-INF/services` |
| `core` | the engine (executor, worker, queues, scheduler, JDBC, storage, run context) |
| `plugin-core` | 23 built-in tasks/triggers (SDK-only — dogfoods the wall) |
| `plugin-example` | sample third-party plugin (`com.acme.*`) |
| `webserver` | Spring MVC REST + SSE |
| `cli` | `tranto` command (picocli) + the showcase/integration tests |

Deeper design: [`06-detailed-architecture.md`](./06-detailed-architecture.md),
the stability wall in [`08-plugin-ecosystem-and-stability.md`](./08-plugin-ecosystem-and-stability.md).

---

## 9. Not yet operable (known limits)

The standalone engine is production-shaped but single-process. **Not** yet available:
multi-process distributed mode (`server executor|worker|scheduler`), the Vue UI, auth/tenancy,
polling/flow triggers, and Docker/K8s task runners. See [`STATUS.md`](./STATUS.md) for the full list.
