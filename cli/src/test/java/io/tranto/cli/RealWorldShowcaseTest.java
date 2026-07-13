package io.tranto.cli;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.schedulers.Scheduler;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs 20 real-world workflows — the kinds of things teams actually automate (onboarding, ETL,
 * approvals, CI, scheduled digests, rate-limited singletons) — end-to-end through the engine, and
 * prints a results table. Each row asserts the expected terminal outcome, so this both demonstrates
 * the platform and guards the whole feature surface at once.
 */
class RealWorldShowcaseTest {

    private record Row(int n, String workflow, String feature, String expected, String actual, boolean ok) {
    }

    private final List<Row> rows = new ArrayList<>();

    private static Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    /** Run a flow to completion and record the row. */
    private void runCase(final int n, final String workflow, final String feature,
                         final StateType expected, final String yaml, final Map<String, Object> inputs) {
        String actual;
        boolean ok;
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution execution = engine.run(parse(yaml), inputs, Duration.ofSeconds(20));
            actual = execution.getState().current().name();
            ok = execution.getState().current() == expected;
        } catch (Exception e) {
            actual = "ERROR: " + e.getMessage();
            ok = false;
        }
        rows.add(new Row(n, workflow, feature, expected.name(), actual, ok));
    }

    private static Execution await(final StandaloneEngine engine, final String id,
                                   final Predicate<Execution> cond, final long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Execution ex = engine.executions().findById(id).orElse(null);
            if (ex != null && cond.test(ex)) {
                return ex;
            }
            Thread.sleep(20);
        }
        return engine.executions().findById(id).orElse(null);
    }

    @Test
    void runsTwentyRealWorldWorkflows() throws Exception {
        // 1) User onboarding — a sequential pipeline driven by an input.
        runCase(1, "user-onboarding", "Sequential + inputs", StateType.SUCCESS, """
            id: user_onboarding
            namespace: company.users
            inputs:
              - id: email
                type: STRING
            tasks:
              - id: validate
                type: io.tranto.plugin.core.log.Log
                message: "Validating {{ inputs.email }}"
              - id: create_account
                type: io.tranto.plugin.core.debug.Return
                format: "account:{{ inputs.email }}"
              - id: send_welcome
                type: io.tranto.plugin.core.log.Log
                message: "Welcome email sent to {{ inputs.email }}"
            """, Map.of("email", "ada@example.com"));

        // 2) Parallel ETL — extract from several sources at once.
        runCase(2, "parallel-etl", "Parallel fan-out", StateType.SUCCESS, """
            id: parallel_etl
            namespace: data
            tasks:
              - id: extract
                type: io.tranto.plugin.core.flow.Parallel
                tasks:
                  - id: from_crm
                    type: io.tranto.plugin.core.log.Log
                    message: "pulling CRM"
                  - id: from_billing
                    type: io.tranto.plugin.core.log.Log
                    message: "pulling billing"
                  - id: from_events
                    type: io.tranto.plugin.core.log.Log
                    message: "pulling events"
              - id: load
                type: io.tranto.plugin.core.log.Log
                message: "loaded warehouse"
            """, Map.of());

        // 3) Order fulfillment — route by order type.
        runCase(3, "order-fulfillment", "Switch/branch", StateType.SUCCESS, """
            id: order_fulfillment
            namespace: shop
            inputs:
              - id: kind
                type: STRING
                defaults: digital
            tasks:
              - id: route
                type: io.tranto.plugin.core.flow.Switch
                value: "{{ inputs.kind }}"
                cases:
                  physical:
                    - id: ship
                      type: io.tranto.plugin.core.log.Log
                      message: "printing shipping label"
                  digital:
                    - id: deliver
                      type: io.tranto.plugin.core.log.Log
                      message: "emailing download link"
                defaults:
                  - id: unknown
                    type: io.tranto.plugin.core.log.Log
                    message: "manual review"
            """, Map.of("kind", "digital"));

        // 4) Feature rollout — conditionally enable.
        runCase(4, "feature-rollout", "If / Else", StateType.SUCCESS, """
            id: feature_rollout
            namespace: platform
            inputs:
              - id: enabled
                type: BOOLEAN
                defaults: true
            tasks:
              - id: decide
                type: io.tranto.plugin.core.flow.If
                condition: "{{ inputs.enabled }}"
                then:
                  - id: turn_on
                    type: io.tranto.plugin.core.log.Log
                    message: "feature enabled for cohort"
                else:
                  - id: keep_off
                    type: io.tranto.plugin.core.log.Log
                    message: "feature stays off"
            """, Map.of("enabled", true));

        // 5) Batch resize — one iteration per file.
        runCase(5, "batch-image-resize", "Loop / ForEach", StateType.SUCCESS, """
            id: batch_resize
            namespace: media
            tasks:
              - id: each_file
                type: io.tranto.plugin.core.flow.Loop
                values: "avatar.png,banner.jpg,thumb.gif"
                tasks:
                  - id: resize
                    type: io.tranto.plugin.core.log.Log
                    message: "resizing {{ taskrun.value }}"
            """, Map.of());

        // 6) Payment retry — flaky charge retried with exponential backoff, then gives up.
        runCase(6, "payment-retry", "Retry + backoff", StateType.FAILED, """
            id: payment_retry
            namespace: billing
            tasks:
              - id: charge
                type: io.tranto.plugin.core.execution.Fail
                message: "gateway timeout"
                retry:
                  maxAttempts: 2
                  behavior: EXPONENTIAL
                  delay: "PT0.05S"
            """, Map.of());

        // 7) API timeout guard — a slow call is capped and fails.
        runCase(7, "api-timeout-guard", "Timeout", StateType.FAILED, """
            id: api_timeout
            namespace: integrations
            tasks:
              - id: call_partner
                type: io.tranto.plugin.core.flow.Sleep
                duration: "PT5S"
                timeout: "PT0.1S"
            """, Map.of());

        // 8) Nightly cleanup — best-effort step that's allowed to fail.
        runCase(8, "nightly-cleanup", "allowFailure -> WARNING", StateType.WARNING, """
            id: nightly_cleanup
            namespace: ops
            tasks:
              - id: purge_temp
                type: io.tranto.plugin.core.execution.Fail
                message: "temp dir locked"
                allowFailure: true
              - id: report
                type: io.tranto.plugin.core.log.Log
                message: "cleanup finished"
            """, Map.of());

        // 9) Conditional release — skip the deploy when not on main.
        runCase(9, "conditional-release", "runIf skip", StateType.SUCCESS, """
            id: conditional_release
            namespace: cicd
            inputs:
              - id: branch
                type: STRING
                defaults: feature-x
            tasks:
              - id: deploy
                type: io.tranto.plugin.core.log.Log
                message: "deploying"
                runIf: "{{ inputs.branch == 'main' }}"
              - id: done
                type: io.tranto.plugin.core.log.Log
                message: "pipeline complete"
            """, Map.of("branch", "feature-x"));

        subflowScenario();          // 10
        pauseApprovalScenario();    // 11

        // 12) Monthly report — typed inputs with defaults + coercion.
        runCase(12, "monthly-report", "Typed inputs", StateType.SUCCESS, """
            id: monthly_report
            namespace: finance
            inputs:
              - id: month
                type: STRING
                defaults: January
              - id: year
                type: INT
              - id: verbose
                type: BOOLEAN
                defaults: false
            tasks:
              - id: build
                type: io.tranto.plugin.core.log.Log
                message: "report for {{ inputs.month }} {{ inputs.year }} (verbose={{ inputs.verbose }})"
            """, Map.of("year", "2026"));

        // 13) Build summary — flow outputs rendered from a task output.
        runCase(13, "build-summary", "Flow outputs", StateType.SUCCESS, """
            id: build_summary
            namespace: cicd
            outputs:
              - id: artifact
                value: "{{ outputs.compile.value }}"
            tasks:
              - id: compile
                type: io.tranto.plugin.core.debug.Return
                format: "app-1.4.2.jar"
            """, Map.of());

        concurrencyScenario();      // 14

        // 15) Page-view counter — state persisted in the KV store within a run.
        runCase(15, "page-view-counter", "KV store", StateType.SUCCESS, """
            id: page_view_counter
            namespace: analytics
            tasks:
              - id: seed
                type: io.tranto.plugin.core.kv.Set
                key: views
                value: "1024"
              - id: read
                type: io.tranto.plugin.core.kv.Get
                key: views
              - id: show
                type: io.tranto.plugin.core.log.Log
                message: "views so far: {{ outputs.read.value }}"
            """, Map.of());

        // 16) CI build — real shell commands via the Process runner.
        runCase(16, "ci-build", "Shell Commands", StateType.SUCCESS, """
            id: ci_build
            namespace: cicd
            tasks:
              - id: build
                type: io.tranto.plugin.core.script.Commands
                commands:
                  - echo compiling sources
                  - echo running tests
                  - echo BUILD OK
            """, Map.of());

        // 17) Request tracing — templating helpers for ids and timestamps.
        runCase(17, "request-tracing", "Pebble now/uuid/json", StateType.SUCCESS, """
            id: request_tracing
            namespace: platform
            tasks:
              - id: stamp
                type: io.tranto.plugin.core.output.OutputValues
                values:
                  request_id: "{{ uuid() }}"
                  at: "{{ now() }}"
                  payload: "{{ json({'ok': true}) }}"
              - id: log_it
                type: io.tranto.plugin.core.log.Log
                message: "traced {{ outputs.stamp.values.request_id }}"
            """, Map.of());

        // 18) Resilient job — main fails, error handler + finally still run.
        runCase(18, "resilient-job", "errors + finally", StateType.FAILED, """
            id: resilient_job
            namespace: ops
            tasks:
              - id: main
                type: io.tranto.plugin.core.execution.Fail
                message: "disk full"
            errors:
              - id: alert
                type: io.tranto.plugin.core.log.Log
                message: "paging on-call"
            finally:
              - id: release_lock
                type: io.tranto.plugin.core.log.Log
                message: "lock released"
            """, Map.of());

        // 19) Data-quality gate — assert invariants before publishing.
        runCase(19, "data-quality-gate", "Assert", StateType.SUCCESS, """
            id: data_quality_gate
            namespace: data
            inputs:
              - id: rows
                type: INT
            tasks:
              - id: check
                type: io.tranto.plugin.core.execution.Assert
                conditions:
                  - "{{ inputs.rows > 0 }}"
              - id: publish
                type: io.tranto.plugin.core.log.Log
                message: "publishing {{ inputs.rows }} rows"
            """, Map.of("rows", "500"));

        cronScenario();             // 20

        printReport();

        List<Row> failures = rows.stream().filter(r -> !r.ok()).toList();
        assertThat(failures)
            .withFailMessage("These workflows did not produce their expected outcome: %s", failures)
            .isEmpty();
        assertThat(rows).hasSize(20);
    }

    /** 10) ETL parent that calls a child flow and mirrors its outcome. */
    private void subflowScenario() {
        String actual;
        boolean ok;
        try (StandaloneEngine engine = new StandaloneEngine()) {
            engine.register(parse("""
                id: enrich_child
                namespace: data
                tasks:
                  - id: enrich
                    type: io.tranto.plugin.core.log.Log
                    message: "enriching records"
                """));
            Execution execution = engine.run(parse("""
                id: etl_parent
                namespace: data
                tasks:
                  - id: call_enrich
                    type: io.tranto.plugin.core.flow.Subflow
                    namespace: data
                    flowId: enrich_child
                  - id: finish
                    type: io.tranto.plugin.core.log.Log
                    message: "pipeline done"
                """), Map.of(), Duration.ofSeconds(20));
            actual = execution.getState().current().name();
            ok = execution.getState().current() == StateType.SUCCESS;
        } catch (Exception e) {
            actual = "ERROR: " + e.getMessage();
            ok = false;
        }
        rows.add(new Row(10, "data-pipeline", "Subflow", "SUCCESS", actual, ok));
    }

    /** 11) Expense approval that pauses for a human, then resumes to success. */
    private void pauseApprovalScenario() {
        String actual;
        boolean ok;
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Flow flow = parse("""
                id: expense_approval
                namespace: finance
                tasks:
                  - id: gate
                    type: io.tranto.plugin.core.flow.Pause
                  - id: reimburse
                    type: io.tranto.plugin.core.log.Log
                    message: "reimbursement issued"
                """);
            Execution submitted = engine.submit(flow, Map.of());
            await(engine, submitted.getId(), e -> e.getState().current() == StateType.PAUSED, 5000);
            engine.resume(submitted.getId());
            Execution done = await(engine, submitted.getId(), e -> e.getState().isTerminated(), 5000);
            actual = done.getState().current().name() + " (paused→approved)";
            ok = done.getState().current() == StateType.SUCCESS;
        } catch (Exception e) {
            actual = "ERROR: " + e.getMessage();
            ok = false;
        }
        rows.add(new Row(11, "expense-approval", "Pause / Resume", "SUCCESS", actual, ok));
    }

    /** 14) Singleton job with concurrency limit 1 — a second run is queued. */
    private void concurrencyScenario() {
        String actual;
        boolean ok;
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Flow flow = parse("""
                id: singleton_job
                namespace: ops
                concurrency:
                  limit: 1
                  behavior: QUEUE
                tasks:
                  - id: gate
                    type: io.tranto.plugin.core.flow.Pause
                  - id: work
                    type: io.tranto.plugin.core.log.Log
                    message: "running the singleton"
                """);
            Execution first = engine.submit(flow, Map.of());
            await(engine, first.getId(), e -> e.getState().current() == StateType.PAUSED, 5000);
            Execution second = engine.submit(flow, Map.of());
            Execution queued = await(engine, second.getId(), e -> e.getState().current() == StateType.QUEUED, 5000);
            actual = "2nd run " + queued.getState().current().name();
            ok = queued.getState().current() == StateType.QUEUED;
            engine.resume(first.getId()); // let the first drain
        } catch (Exception e) {
            actual = "ERROR: " + e.getMessage();
            ok = false;
        }
        rows.add(new Row(14, "singleton-job", "Concurrency limit", "QUEUED", actual, ok));
    }

    /** 20) Daily digest driven by a cron schedule — the scheduler fires it when due. */
    private void cronScenario() {
        String actual;
        boolean ok;
        try {
            Flow flow = parse("""
                id: daily_digest
                namespace: reporting
                triggers:
                  - id: every_minute
                    type: io.tranto.plugin.core.trigger.Schedule
                    cron: "* * * * *"
                    timezone: UTC
                tasks:
                  - id: send
                    type: io.tranto.plugin.core.log.Log
                    message: "digest sent"
                """);
            AtomicInteger fired = new AtomicInteger();
            try (Scheduler scheduler = new Scheduler((f, t) -> fired.incrementAndGet())) {
                ZonedDateTime t0 = ZonedDateTime.of(2026, 7, 5, 9, 0, 30, 0, ZoneOffset.UTC);
                scheduler.register(flow, t0);
                scheduler.tick(t0.plusMinutes(1).plusSeconds(5));
            }
            actual = fired.get() == 1 ? "FIRED (1x)" : "fired " + fired.get() + "x";
            ok = fired.get() == 1;
        } catch (Exception e) {
            actual = "ERROR: " + e.getMessage();
            ok = false;
        }
        rows.add(new Row(20, "daily-digest", "Cron trigger", "FIRED", actual, ok));
    }

    private void printReport() {
        rows.sort((a, b) -> Integer.compare(a.n(), b.n()));
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== TRANTO — 20 REAL-WORLD WORKFLOWS ====================\n");
        sb.append(String.format("%-3s %-22s %-24s %-10s %-22s %s%n",
            "#", "WORKFLOW", "FEATURE", "EXPECTED", "ACTUAL", "OK"));
        sb.append("-------------------------------------------------------------------------\n");
        for (Row r : rows) {
            sb.append(String.format("%-3d %-22s %-24s %-10s %-22s %s%n",
                r.n(), r.workflow(), r.feature(), r.expected(), r.actual(), r.ok() ? "PASS" : "FAIL"));
        }
        long passed = rows.stream().filter(Row::ok).count();
        sb.append("-------------------------------------------------------------------------\n");
        sb.append(String.format("  %d / %d workflows produced their expected outcome%n", passed, rows.size()));
        sb.append("=========================================================================\n");
        System.out.println(sb);
    }
}
