package io.tranto.core.queues;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the JDBC queue is {@code at-least-once}: a message whose consumer fails (a crash proxy) is
 * <em>not</em> lost — its lease expires and it is redelivered until acked. This is the fix for the
 * "marked consumed before the consumer ran" data-loss path (Landmine #1).
 */
class JdbcQueueRedeliveryTest {

    @Test
    void redeliversAmessageWhoseConsumerFailsUntilItSucceeds() throws Exception {
        JdbcDatabase db = JdbcDatabase.h2InMemory("queue_redeliver");
        ObjectMapper mapper = new ObjectMapper();

        // Short 200ms lease so the redelivery happens quickly.
        try (JdbcQueue<String> queue = new JdbcQueue<>(db, "jobs", String.class, mapper, 200)) {
            AtomicInteger deliveries = new AtomicInteger();
            List<String> succeeded = new CopyOnWriteArrayList<>();

            queue.receive(message -> {
                // Fail (throw) on the first delivery, succeed on the redelivery.
                if (deliveries.incrementAndGet() == 1) {
                    throw new RuntimeException("consumer crashed before acking");
                }
                succeeded.add(message);
            });

            queue.emit("payload-42");

            awaitTrue(() -> !succeeded.isEmpty(), 5000);

            assertThat(deliveries.get()).as("first delivery failed, so it must be redelivered").isGreaterThanOrEqualTo(2);
            assertThat(succeeded).containsExactly("payload-42");

            // Once the consumer succeeded, the row is acked (consumed) — no perpetual redelivery.
            awaitTrue(() -> uncheckedUnconsumed(db, "jobs") == 0, 5000);
            assertThat(unconsumed(db, "jobs")).isZero();
        }
    }

    private static void awaitTrue(final BooleanSupplier condition, final long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
    }

    private static int uncheckedUnconsumed(final JdbcDatabase db, final String topic) {
        try {
            return unconsumed(db, topic);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int unconsumed(final JdbcDatabase db, final String topic) throws Exception {
        try (Connection c = db.connection();
             PreparedStatement s = c.prepareStatement(
                 "SELECT COUNT(*) FROM queue_messages WHERE topic = ? AND consumed = FALSE")) {
            s.setString(1, topic);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
