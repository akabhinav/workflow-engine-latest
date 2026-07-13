package io.tranto.cli;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ten genuinely complex, database-driven workflows — the kind of thing data/backend teams run in
 * production: fraud screening, ETL dedupe/aggregate, inventory reconciliation, LTV segmentation,
 * double-entry ledger checks, SCD Type-2, a data-quality gate, event rollups, idempotent upserts,
 * and partitioned subflow fan-out. Each runs real SQL against H2 and is verified by inspecting the
 * final database state — not just the execution status.
 */
class ComplexDbWorkflowsTest {

    private record Row(int n, String workflow, String state, boolean ok, String verified) {
    }

    private final List<Row> rows = new ArrayList<>();

    private static Flow parse(final String yaml) throws Exception {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        return new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
    }

    /** Read a single numeric cell from the DB (for verifying end state). */
    private static long scalar(final String url, final String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarStr(final String url, final String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void record(final int n, final String name, final Execution ex, final boolean verified,
                        final String detail) {
        boolean ok = ex.getState().current() == StateType.SUCCESS && verified;
        rows.add(new Row(n, name, ex.getState().current().name(), ok, detail));
    }

    private void fail(final int n, final String name, final Exception e) {
        rows.add(new Row(n, name, "ERROR", false, e.getClass().getSimpleName() + ": " + e.getMessage()));
    }

    @Test
    void runsTenComplexDatabaseWorkflows() {
        orderFraudScreening();       // 1
        etlDedupeAggregate();        // 2
        inventoryReconciliation();   // 3
        customerLtvSegmentation();   // 4
        doubleEntryLedger();         // 5
        scdType2();                  // 6
        dataQualityGate();           // 7
        eventRollup();               // 8
        idempotentUpsert();          // 9
        partitionedFanout();         // 10

        printReport();
        List<Row> failures = rows.stream().filter(r -> !r.ok()).toList();
        assertThat(failures).withFailMessage("Failed workflows: %s", failures).isEmpty();
        assertThat(rows).hasSize(10);
    }

    // 1) Fraud screening + fulfillment: set-based screening, per-row audit loop, parallel counts,
    //    subflow notify, KV cursor, data-quality assert, flow outputs.
    private void orderFraudScreening() {
        String url = "jdbc:h2:mem:cx1;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            engine.register(parse("""
                id: notify_child
                namespace: shop
                tasks:
                  - id: notify
                    type: io.tranto.plugin.core.log.Log
                    message: "notifications dispatched"
                """));
            Execution ex = engine.run(parse("""
                id: fraud_pipeline
                namespace: shop
                inputs:
                  - id: dbUrl
                    type: STRING
                outputs:
                  - id: reviewed
                    value: "{{ outputs.count_review.firstValue }}"
                  - id: processing
                    value: "{{ outputs.count_processing.firstValue }}"
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE customers(id INT PRIMARY KEY, name VARCHAR(50), risk INT);
                      CREATE TABLE orders(id INT PRIMARY KEY, customer_id INT, amount INT, status VARCHAR(20));
                      CREATE TABLE audit(id INT AUTO_INCREMENT PRIMARY KEY, order_id INT, action VARCHAR(20));
                      INSERT INTO customers VALUES (1,'Ada',20),(2,'Bob',85),(3,'Cid',10);
                      INSERT INTO orders VALUES (1,1,500,'PENDING'),(2,1,1500,'PENDING'),(3,2,300,'PENDING'),(4,3,200,'PENDING'),(5,3,2000,'PENDING')
                  - id: screen
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      UPDATE orders SET status='REVIEW'
                      WHERE status='PENDING' AND (amount > 1000
                        OR customer_id IN (SELECT id FROM customers WHERE risk > 70))
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
                      - id: revenue
                        type: io.tranto.plugin.core.jdbc.Query
                        url: "{{ inputs.dbUrl }}"
                        sql: "SELECT COALESCE(SUM(amount),0) FROM orders WHERE status='PROCESSING'"
                  - id: get_ids
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT id FROM orders WHERE status='PROCESSING' ORDER BY id"
                  - id: audit_each
                    type: io.tranto.plugin.core.flow.Loop
                    values: "{{ outputs.get_ids.firstColumn }}"
                    tasks:
                      - id: write_audit
                        type: io.tranto.plugin.core.jdbc.Execute
                        url: "{{ inputs.dbUrl }}"
                        sql: "INSERT INTO audit(order_id, action) VALUES ({{ taskrun.value }}, 'PROCESSING')"
                  - id: check_pending
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM orders WHERE status='PENDING'"
                  - id: quality_gate
                    type: io.tranto.plugin.core.execution.Assert
                    conditions:
                      - "{{ outputs.check_pending.firstValue == 0 }}"
                  - id: save_cursor
                    type: io.tranto.plugin.core.kv.Set
                    key: last_run
                    value: "processed {{ outputs.count_processing.firstValue }}"
                  - id: notify
                    type: io.tranto.plugin.core.flow.Subflow
                    namespace: shop
                    flowId: notify_child
                finally:
                  - id: done
                    type: io.tranto.plugin.core.log.Log
                    message: "pipeline complete"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long review = scalar(url, "SELECT COUNT(*) FROM orders WHERE status='REVIEW'");
            long proc = scalar(url, "SELECT COUNT(*) FROM orders WHERE status='PROCESSING'");
            long audit = scalar(url, "SELECT COUNT(*) FROM audit");
            long pending = scalar(url, "SELECT COUNT(*) FROM orders WHERE status='PENDING'");
            boolean ok = review == 3 && proc == 2 && audit == 2 && pending == 0;
            record(1, "order-fraud-screening", ex, ok,
                "REVIEW=" + review + " PROCESSING=" + proc + " audit_rows=" + audit + " pending=" + pending);
        } catch (Exception e) {
            fail(1, "order-fraud-screening", e);
        }
    }

    // 2) ETL: ingest raw (with dups) -> dedupe into clean -> aggregate into daily metrics.
    private void etlDedupeAggregate() {
        String url = "jdbc:h2:mem:cx2;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: etl_pipeline
                namespace: data
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE raw_events(user_id INT, d VARCHAR(10), kind VARCHAR(10));
                      CREATE TABLE clean_events(user_id INT, d VARCHAR(10), kind VARCHAR(10));
                      CREATE TABLE daily_metrics(d VARCHAR(10), cnt INT);
                      INSERT INTO raw_events VALUES (1,'d1','click'),(1,'d1','click'),(2,'d1','view'),(3,'d2','click'),(3,'d2','click')
                  - id: dedupe
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "INSERT INTO clean_events SELECT DISTINCT user_id, d, kind FROM raw_events"
                  - id: aggregate
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "INSERT INTO daily_metrics SELECT d, COUNT(*) FROM clean_events GROUP BY d"
                  - id: verify
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM clean_events"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "clean rows: {{ outputs.verify.firstValue }}"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long raw = scalar(url, "SELECT COUNT(*) FROM raw_events");
            long clean = scalar(url, "SELECT COUNT(*) FROM clean_events");
            long days = scalar(url, "SELECT COUNT(*) FROM daily_metrics");
            long d1 = scalar(url, "SELECT cnt FROM daily_metrics WHERE d='d1'");
            boolean ok = raw == 5 && clean == 3 && days == 2 && d1 == 2;
            record(2, "etl-dedupe-aggregate", ex, ok,
                "raw=" + raw + " clean=" + clean + " days=" + days + " d1_cnt=" + d1);
        } catch (Exception e) {
            fail(2, "etl-dedupe-aggregate", e);
        }
    }

    // 3) Inventory reconciliation: compute on-hand vs ledger, flag oversold SKUs.
    private void inventoryReconciliation() {
        String url = "jdbc:h2:mem:cx3;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: inventory_recon
                namespace: ops
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE inventory(sku VARCHAR(5) PRIMARY KEY, on_hand INT);
                      CREATE TABLE ledger(sku VARCHAR(5), delta INT);
                      CREATE TABLE discrepancies(sku VARCHAR(5), computed INT);
                      INSERT INTO inventory VALUES ('A',100),('B',50),('C',5);
                      INSERT INTO ledger VALUES ('A',-30),('B',-5),('C',-20),('C',-3)
                  - id: reconcile
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      INSERT INTO discrepancies
                      SELECT sku, computed FROM (
                        SELECT i.sku AS sku, i.on_hand + COALESCE(SUM(l.delta),0) AS computed
                        FROM inventory i LEFT JOIN ledger l ON i.sku = l.sku
                        GROUP BY i.sku, i.on_hand
                      ) x WHERE computed < 0
                  - id: count
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM discrepancies"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "oversold SKUs: {{ outputs.count.firstValue }}"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long disc = scalar(url, "SELECT COUNT(*) FROM discrepancies");
            String sku = disc == 1 ? scalarStr(url, "SELECT sku FROM discrepancies") : "";
            boolean ok = disc == 1 && "C".equals(sku);
            record(3, "inventory-reconciliation", ex, ok, "discrepancies=" + disc + " sku=" + sku);
        } catch (Exception e) {
            fail(3, "inventory-reconciliation", e);
        }
    }

    // 4) Customer LTV segmentation into GOLD/SILVER/BRONZE tiers.
    private void customerLtvSegmentation() {
        String url = "jdbc:h2:mem:cx4;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: ltv_segmentation
                namespace: crm
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE orders(customer_id INT, amount INT);
                      CREATE TABLE ltv(customer_id INT, total INT, tier VARCHAR(10));
                      INSERT INTO orders VALUES (1,100),(1,200),(2,1500),(3,50)
                  - id: segment
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      INSERT INTO ltv
                      SELECT customer_id, SUM(amount),
                        CASE WHEN SUM(amount) >= 1000 THEN 'GOLD'
                             WHEN SUM(amount) >= 250  THEN 'SILVER'
                             ELSE 'BRONZE' END
                      FROM orders GROUP BY customer_id
                  - id: gold
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM ltv WHERE tier='GOLD'"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "gold customers: {{ outputs.gold.firstValue }}"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long gold = scalar(url, "SELECT COUNT(*) FROM ltv WHERE tier='GOLD'");
            long silver = scalar(url, "SELECT COUNT(*) FROM ltv WHERE tier='SILVER'");
            long bronze = scalar(url, "SELECT COUNT(*) FROM ltv WHERE tier='BRONZE'");
            boolean ok = gold == 1 && silver == 1 && bronze == 1;
            record(4, "customer-ltv-segmentation", ex, ok,
                "GOLD=" + gold + " SILVER=" + silver + " BRONZE=" + bronze);
        } catch (Exception e) {
            fail(4, "customer-ltv-segmentation", e);
        }
    }

    // 5) Double-entry ledger: assert debits == credits before posting balances.
    private void doubleEntryLedger() {
        String url = "jdbc:h2:mem:cx5;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: ledger_check
                namespace: finance
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE journal(txn INT, account VARCHAR(20), debit INT, credit INT);
                      INSERT INTO journal VALUES
                        (1,'cash',100,0),(1,'revenue',0,100),
                        (2,'expense',40,0),(2,'cash',0,40)
                  - id: debits
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT SUM(debit) FROM journal"
                  - id: credits
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT SUM(credit) FROM journal"
                  - id: balanced
                    type: io.tranto.plugin.core.execution.Assert
                    conditions:
                      - "{{ outputs.debits.firstValue == outputs.credits.firstValue }}"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "ledger balanced at {{ outputs.debits.firstValue }}"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long debit = scalar(url, "SELECT SUM(debit) FROM journal");
            long credit = scalar(url, "SELECT SUM(credit) FROM journal");
            boolean ok = debit == credit && debit == 140;
            record(5, "double-entry-ledger", ex, ok, "debits=" + debit + " credits=" + credit);
        } catch (Exception e) {
            fail(5, "double-entry-ledger", e);
        }
    }

    // 6) SCD Type 2: expire the old version, insert a new current version.
    private void scdType2() {
        String url = "jdbc:h2:mem:cx6;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: scd2
                namespace: data
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE dim_customer(id INT AUTO_INCREMENT PRIMARY KEY, cust_key INT,
                        name VARCHAR(50), valid_to VARCHAR(10), current BOOLEAN);
                      INSERT INTO dim_customer(cust_key,name,valid_to,current) VALUES (100,'Ada Old','9999',TRUE)
                  - id: expire
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "UPDATE dim_customer SET current=FALSE, valid_to='2026-07-05' WHERE cust_key=100 AND current=TRUE"
                  - id: insert_new
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "INSERT INTO dim_customer(cust_key,name,valid_to,current) VALUES (100,'Ada New','9999',TRUE)"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "SCD2 version applied"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long versions = scalar(url, "SELECT COUNT(*) FROM dim_customer WHERE cust_key=100");
            long current = scalar(url, "SELECT COUNT(*) FROM dim_customer WHERE cust_key=100 AND current=TRUE");
            String name = scalarStr(url, "SELECT name FROM dim_customer WHERE cust_key=100 AND current=TRUE");
            boolean ok = versions == 2 && current == 1 && "Ada New".equals(name);
            record(6, "scd-type-2", ex, ok, "versions=" + versions + " current=" + current + " name=" + name);
        } catch (Exception e) {
            fail(6, "scd-type-2", e);
        }
    }

    // 7) Data-quality gate: multiple checks (nulls, negatives, duplicates) must all pass.
    private void dataQualityGate() {
        String url = "jdbc:h2:mem:cx7;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: dq_gate
                namespace: data
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE metrics(id INT, val INT);
                      INSERT INTO metrics VALUES (1,10),(2,20),(3,30),(4,40)
                  - id: nulls
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM metrics WHERE val IS NULL"
                  - id: negatives
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM metrics WHERE val < 0"
                  - id: dups
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM (SELECT id FROM metrics GROUP BY id HAVING COUNT(*) > 1) t"
                  - id: gate
                    type: io.tranto.plugin.core.execution.Assert
                    conditions:
                      - "{{ outputs.nulls.firstValue == 0 }}"
                      - "{{ outputs.negatives.firstValue == 0 }}"
                      - "{{ outputs.dups.firstValue == 0 }}"
                  - id: publish
                    type: io.tranto.plugin.core.log.Log
                    message: "all data-quality checks passed"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long total = scalar(url, "SELECT COUNT(*) FROM metrics");
            boolean ok = total == 4;
            record(7, "data-quality-gate", ex, ok, "checked_rows=" + total + " all_checks=PASS");
        } catch (Exception e) {
            fail(7, "data-quality-gate", e);
        }
    }

    // 8) Event rollup: raw events aggregated into hourly buckets.
    private void eventRollup() {
        String url = "jdbc:h2:mem:cx8;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: event_rollup
                namespace: analytics
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE events(user_id INT, hr INT);
                      CREATE TABLE hourly(hr INT, cnt INT);
                      INSERT INTO events VALUES (1,9),(1,9),(1,10),(2,9)
                  - id: rollup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "INSERT INTO hourly SELECT hr, COUNT(*) FROM events GROUP BY hr"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "rolled up events into hourly buckets"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long buckets = scalar(url, "SELECT COUNT(*) FROM hourly");
            long hour9 = scalar(url, "SELECT cnt FROM hourly WHERE hr=9");
            boolean ok = buckets == 2 && hour9 == 3;
            record(8, "event-rollup", ex, ok, "buckets=" + buckets + " hour9_cnt=" + hour9);
        } catch (Exception e) {
            fail(8, "event-rollup", e);
        }
    }

    // 9) Idempotent upsert: run a MERGE twice; row count stays stable, value updates.
    private void idempotentUpsert() {
        String url = "jdbc:h2:mem:cx9;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            Execution ex = engine.run(parse("""
                id: idempotent_upsert
                namespace: data
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "CREATE TABLE targets(id INT PRIMARY KEY, name VARCHAR(20))"
                  - id: upsert_first
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "MERGE INTO targets(id,name) KEY(id) VALUES (1,'v1'),(2,'v2')"
                    retry:
                      maxAttempts: 3
                      behavior: EXPONENTIAL
                      delay: "PT0.05S"
                  - id: upsert_again
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: "MERGE INTO targets(id,name) KEY(id) VALUES (1,'v1-updated'),(2,'v2')"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "upsert applied idempotently"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long count = scalar(url, "SELECT COUNT(*) FROM targets");
            String name1 = scalarStr(url, "SELECT name FROM targets WHERE id=1");
            boolean ok = count == 2 && "v1-updated".equals(name1);
            record(9, "idempotent-upsert", ex, ok, "rows=" + count + " id1_name=" + name1);
        } catch (Exception e) {
            fail(9, "idempotent-upsert", e);
        }
    }

    // 10) Partitioned fan-out: parent seeds, three region subflows process their slice in parallel.
    private void partitionedFanout() {
        String url = "jdbc:h2:mem:cx10;DB_CLOSE_DELAY=-1";
        try (StandaloneEngine engine = new StandaloneEngine()) {
            for (String region : List.of("US", "EU", "APAC")) {
                engine.register(parse("""
                    id: region_%s
                    namespace: regions
                    tasks:
                      - id: process
                        type: io.tranto.plugin.core.jdbc.Execute
                        url: "%s"
                        sql: "UPDATE orders SET processed=TRUE WHERE region='%s'"
                    """.formatted(region.toLowerCase(), url, region)));
            }
            Execution ex = engine.run(parse("""
                id: regional_fanout
                namespace: regions
                inputs:
                  - id: dbUrl
                    type: STRING
                tasks:
                  - id: setup
                    type: io.tranto.plugin.core.jdbc.Execute
                    url: "{{ inputs.dbUrl }}"
                    sql: |
                      CREATE TABLE orders(id INT PRIMARY KEY, region VARCHAR(5), processed BOOLEAN);
                      INSERT INTO orders VALUES (1,'US',FALSE),(2,'US',FALSE),(3,'EU',FALSE),(4,'EU',FALSE),(5,'APAC',FALSE)
                  - id: fan_out
                    type: io.tranto.plugin.core.flow.Parallel
                    tasks:
                      - id: run_us
                        type: io.tranto.plugin.core.flow.Subflow
                        namespace: regions
                        flowId: region_us
                      - id: run_eu
                        type: io.tranto.plugin.core.flow.Subflow
                        namespace: regions
                        flowId: region_eu
                      - id: run_apac
                        type: io.tranto.plugin.core.flow.Subflow
                        namespace: regions
                        flowId: region_apac
                  - id: verify
                    type: io.tranto.plugin.core.jdbc.Query
                    url: "{{ inputs.dbUrl }}"
                    sql: "SELECT COUNT(*) FROM orders WHERE processed=TRUE"
                  - id: log
                    type: io.tranto.plugin.core.log.Log
                    message: "processed {{ outputs.verify.firstValue }} orders across regions"
                """), Map.of("dbUrl", url), Duration.ofSeconds(30));

            long processed = scalar(url, "SELECT COUNT(*) FROM orders WHERE processed=TRUE");
            boolean ok = processed == 5;
            record(10, "partitioned-fanout", ex, ok, "processed=" + processed + "/5 across 3 regions");
        } catch (Exception e) {
            fail(10, "partitioned-fanout", e);
        }
    }

    private void printReport() {
        rows.sort((a, b) -> Integer.compare(a.n(), b.n()));
        StringBuilder sb = new StringBuilder();
        sb.append("\n============ TRANTO — 10 COMPLEX DATABASE WORKFLOWS ============\n");
        sb.append(String.format("%-3s %-27s %-8s %-5s %s%n", "#", "WORKFLOW", "STATE", "OK", "DB VERIFICATION"));
        sb.append("---------------------------------------------------------------\n");
        for (Row r : rows) {
            sb.append(String.format("%-3d %-27s %-8s %-5s %s%n",
                r.n(), r.workflow(), r.state(), r.ok() ? "PASS" : "FAIL", r.verified()));
        }
        long passed = rows.stream().filter(Row::ok).count();
        sb.append("---------------------------------------------------------------\n");
        sb.append(String.format("  %d / %d complex DB workflows succeeded AND verified in-database%n", passed, rows.size()));
        sb.append("===============================================================\n");
        System.out.println(sb);
    }
}
