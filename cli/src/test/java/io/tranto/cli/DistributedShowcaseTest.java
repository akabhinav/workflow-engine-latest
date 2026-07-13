package io.tranto.cli;

import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.DistributedEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full feature surface — sequential, parallel, switch, loop, retry, subflow, KV, and real
 * SQL pipelines — on the JDBC-backed {@link DistributedEngine} (not the in-memory engine). This
 * exercises the distributed transport for real: every {@code WorkerTask}/{@code Execution} is
 * serialized to JSON, queued in the database, and reconstructed on the other side. DB workflows are
 * verified by inspecting the resulting tables after the run.
 */
class DistributedShowcaseTest {

    private record Row(int n, String workflow, String feature, String expected, String actual, boolean ok) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final JacksonMapper mappers = mappers();

    private static JacksonMapper mappers() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new JacksonMapper(registry);
    }

    private Flow parse(final String yaml) throws Exception {
        return new YamlFlowParser(mappers).parse(yaml);
    }

    private static long scalar(final String url, final String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean dbEquals(final String url, final String sql, final long expected) {
        try {
            return scalar(url, sql) == expected;
        } catch (Exception e) {
            return false;
        }
    }

    /** Run a workflow, then verify (post-run) and record the row. */
    private void run(final DistributedEngine engine, final int n, final String name, final String feature,
                     final StateType expected, final String yaml, final BooleanSupplier verifyAfter) {
        try {
            Execution ex = engine.run(parse(yaml), Map.of(), Duration.ofSeconds(30));
            boolean ok = ex.getState().current() == expected && verifyAfter.getAsBoolean();
            rows.add(new Row(n, name, feature, expected.name(), ex.getState().current().name(), ok));
        } catch (Exception e) {
            rows.add(new Row(n, name, feature, expected.name(), "ERROR: " + e.getMessage(), false));
        }
    }

    @Test
    void runsTheFeatureSurfaceOnTheDistributedEngine() throws Exception {
        JdbcDatabase engineDb = JdbcDatabase.h2InMemory("dist_showcase_engine");

        try (DistributedEngine engine = new DistributedEngine(engineDb, mappers)) {
            engine.register(parse("""
                id: dist_child
                namespace: demo
                tasks:
                  - id: c1
                    type: io.tranto.plugin.core.log.Log
                    message: "child ran on the distributed engine"
                """));

            run(engine, 1, "sequential", "Sequential + inputs", StateType.SUCCESS, """
                id: d_seq
                namespace: demo
                inputs:
                  - id: who
                    type: STRING
                    defaults: ada
                tasks:
                  - id: a
                    type: io.tranto.plugin.core.log.Log
                    message: "hi {{ inputs.who }}"
                  - id: b
                    type: io.tranto.plugin.core.debug.Return
                    format: "done-{{ inputs.who }}"
                """, () -> true);

            run(engine, 2, "parallel", "Parallel", StateType.SUCCESS, """
                id: d_par
                namespace: demo
                tasks:
                  - id: p
                    type: io.tranto.plugin.core.flow.Parallel
                    tasks:
                      - id: x
                        type: io.tranto.plugin.core.log.Log
                        message: "x"
                      - id: y
                        type: io.tranto.plugin.core.log.Log
                        message: "y"
                """, () -> true);

            run(engine, 3, "switch", "Switch", StateType.SUCCESS, """
                id: d_switch
                namespace: demo
                tasks:
                  - id: s
                    type: io.tranto.plugin.core.flow.Switch
                    value: "gold"
                    cases:
                      gold:
                        - id: g
                          type: io.tranto.plugin.core.log.Log
                          message: "gold path"
                    defaults:
                      - id: d
                        type: io.tranto.plugin.core.log.Log
                        message: "default"
                """, () -> true);

            run(engine, 4, "loop", "Loop / ForEach", StateType.SUCCESS, """
                id: d_loop
                namespace: demo
                tasks:
                  - id: each
                    type: io.tranto.plugin.core.flow.Loop
                    values: "a,b,c"
                    tasks:
                      - id: it
                        type: io.tranto.plugin.core.log.Log
                        message: "value {{ taskrun.value }}"
                """, () -> true);

            run(engine, 5, "retry", "Retry + backoff", StateType.FAILED, """
                id: d_retry
                namespace: demo
                tasks:
                  - id: boom
                    type: io.tranto.plugin.core.execution.Fail
                    message: "nope"
                    retry:
                      maxAttempts: 2
                      behavior: EXPONENTIAL
                      delay: "PT0.05S"
                """, () -> true);

            run(engine, 6, "subflow", "Subflow", StateType.SUCCESS, """
                id: d_parent
                namespace: demo
                tasks:
                  - id: call
                    type: io.tranto.plugin.core.flow.Subflow
                    namespace: demo
                    flowId: dist_child
                  - id: after
                    type: io.tranto.plugin.core.log.Log
                    message: "parent continues"
                """, () -> true);

            run(engine, 7, "kv-store", "KV store", StateType.SUCCESS, """
                id: d_kv
                namespace: demo
                tasks:
                  - id: put
                    type: io.tranto.plugin.core.kv.Set
                    key: k
                    value: "42"
                  - id: get
                    type: io.tranto.plugin.core.kv.Get
                    key: k
                  - id: show
                    type: io.tranto.plugin.core.log.Log
                    message: "k={{ outputs.get.value }}"
                """, () -> true);

            String etlUrl = "jdbc:h2:mem:dist_etl;DB_CLOSE_DELAY=-1";
            run(engine, 8, "db-etl", "SQL ETL + verify", StateType.SUCCESS, """
                id: d_etl
                namespace: demo
                inputs:
                  - id: dbUrl
                    type: STRING
                    defaults: "%s"
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE raw(u INT, k VARCHAR(5));
                      CREATE TABLE clean(u INT, k VARCHAR(5));
                      INSERT INTO raw VALUES (1,'a'),(1,'a'),(2,'b')
                  - id: dedupe
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "INSERT INTO clean SELECT DISTINCT u,k FROM raw"
                  - id: count
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM clean"
                  - id: verify
                    type: io.tranto.plugin.core.execution.Assert
                    conditions:
                      - "{{ outputs.count.firstValue == 2 }}"
                """.formatted(etlUrl), () -> dbEquals(etlUrl, "SELECT COUNT(*) FROM clean", 2));

            String dbUrl = "jdbc:h2:mem:dist_fraud;DB_CLOSE_DELAY=-1";
            run(engine, 9, "db-fraud-pipeline", "SQL + parallel + loop + assert", StateType.SUCCESS, """
                id: d_fraud
                namespace: demo
                inputs:
                  - id: dbUrl
                    type: STRING
                    defaults: "%s"
                outputs:
                  - id: processing
                    value: "{{ outputs.count_processing.firstValue }}"
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE orders(id INT PRIMARY KEY, amount INT, status VARCHAR(20));
                      CREATE TABLE audit(id INT AUTO_INCREMENT PRIMARY KEY, order_id INT);
                      INSERT INTO orders VALUES (1,500,'PENDING'),(2,1500,'PENDING'),(3,200,'PENDING')
                  - id: screen
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "UPDATE orders SET status='REVIEW' WHERE status='PENDING' AND amount > 1000"
                  - id: process
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "UPDATE orders SET status='PROCESSING' WHERE status='PENDING'"
                  - id: checks
                    type: io.tranto.plugin.core.flow.Parallel
                    tasks:
                      - id: count_review
                        type: io.tranto.plugin.core.jdbc.Query
                        url: "{{ inputs.dbUrl }}"
                        sql: "SELECT COUNT(*) FROM orders WHERE status='REVIEW'"
                      - id: count_processing
                        type: io.tranto.plugin.core.jdbc.Query
                        url: "{{ inputs.dbUrl }}"
                        sql: "SELECT COUNT(*) FROM orders WHERE status='PROCESSING'"
                  - id: ids
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT id FROM orders WHERE status='PROCESSING' ORDER BY id"
                  - id: audit_each
                    type: io.tranto.plugin.core.flow.Loop
                    values: "{{ outputs.ids.firstColumn }}"
                    tasks:
                      - id: aud
                        type: io.tranto.plugin.core.jdbc.Execute
                        url: "{{ inputs.dbUrl }}"
                        sql: "INSERT INTO audit(order_id) VALUES ({{ taskrun.value }})"
                  - id: verify
                    type: io.tranto.plugin.core.execution.Assert
                    conditions:
                      - "{{ outputs.count_review.firstValue == 1 }}"
                      - "{{ outputs.count_processing.firstValue == 2 }}"
                """.formatted(dbUrl),
                () -> dbEquals(dbUrl, "SELECT COUNT(*) FROM audit", 2)
                    && dbEquals(dbUrl, "SELECT COUNT(*) FROM orders WHERE status='REVIEW'", 1));

            printReport();
            List<Row> failures = rows.stream().filter(r -> !r.ok()).toList();
            assertThat(failures).withFailMessage("Failed on distributed engine: %s", failures).isEmpty();
            assertThat(rows).hasSize(9);
        }
    }

    private void printReport() {
        rows.sort((a, b) -> Integer.compare(a.n(), b.n()));
        StringBuilder sb = new StringBuilder();
        sb.append("\n======== TRANTO — FEATURE SURFACE ON THE DISTRIBUTED (JDBC) ENGINE ========\n");
        sb.append(String.format("%-3s %-20s %-30s %-9s %-9s %s%n",
            "#", "WORKFLOW", "FEATURE", "EXPECTED", "ACTUAL", "OK"));
        sb.append("--------------------------------------------------------------------------\n");
        for (Row r : rows) {
            sb.append(String.format("%-3d %-20s %-30s %-9s %-9s %s%n",
                r.n(), r.workflow(), r.feature(), r.expected(), r.actual(), r.ok() ? "PASS" : "FAIL"));
        }
        long passed = rows.stream().filter(Row::ok).count();
        sb.append("--------------------------------------------------------------------------\n");
        sb.append(String.format("  %d / %d ran correctly over the JDBC queue transport%n", passed, rows.size()));
        sb.append("==========================================================================\n");
        System.out.println(sb);
    }
}
