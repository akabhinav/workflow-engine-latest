# Real-world example workflows

Twenty workflows covering the patterns teams actually automate. Each maps to a platform feature and
is exercised end-to-end by `RealWorldShowcaseTest` (the automated proof that all 20 work).

Build first: `mvn clean install`. Then run a flow with the CLI:

```
java -jar cli/target/tranto.jar run examples/real-world/05-batch-image-resize.yaml
```

## Runnable directly with `tranto run` (single flow, inputs defaulted)

| File | Feature | Expected end state |
|------|---------|--------------------|
| 01-user-onboarding.yaml   | Sequential + inputs        | SUCCESS |
| 02-parallel-etl.yaml      | Parallel fan-out           | SUCCESS |
| 03-order-fulfillment.yaml | Switch / branch            | SUCCESS |
| 04-feature-rollout.yaml   | If / Else                  | SUCCESS |
| 05-batch-image-resize.yaml| Loop / ForEach             | SUCCESS |
| 06-payment-retry.yaml     | Retry + exponential backoff| FAILED (retries exhausted, by design) |
| 07-api-timeout-guard.yaml | Timeout enforcement        | FAILED (call capped at 100ms) |
| 08-nightly-cleanup.yaml   | allowFailure               | WARNING |
| 09-conditional-release.yaml| runIf skip                | SUCCESS (deploy skipped off `main`) |
| 12-monthly-report.yaml    | Typed inputs + coercion    | SUCCESS |
| 13-build-summary.yaml     | Flow outputs               | SUCCESS |
| 15-page-view-counter.yaml | KV store                   | SUCCESS |
| 16-ci-build.yaml          | Shell Commands (Process)   | SUCCESS |
| 17-request-tracing.yaml   | Pebble now/uuid/json       | SUCCESS |
| 18-resilient-job.yaml     | errors + finally hooks     | FAILED (handlers still run) |
| 19-data-quality-gate.yaml | Assert                     | SUCCESS |

The "FAILED/WARNING" ones are *supposed* to end that way — they demonstrate the failure semantics
teams depend on (giving up after retries, killing a hung call, tolerating best-effort steps).

## Driven by the engine/API (not a single `tranto run`)

These need orchestration beyond running one flow to completion:

| File | Why |
|------|-----|
| 10-data-pipeline-parent.yaml (+ child) | Subflow — the child must be registered first |
| 11-expense-approval.yaml               | Pause/Resume — stops at PAUSED, needs `resume(id)` |
| 14-singleton-job.yaml                  | Concurrency — submit two runs; the 2nd is QUEUED |
| 20-daily-digest.yaml                   | Cron trigger — the scheduler starts it when due |

See `cli/src/test/java/io/tranto/cli/RealWorldShowcaseTest.java` for how each is driven.
