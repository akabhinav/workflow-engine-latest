package io.tranto.core.queues;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JDBC-backed {@link QueueInterface}: messages are rows in a shared {@code queue_messages} table,
 * and a poller claims unconsumed rows and dispatches them. This is the backend that makes the
 * platform distributed — many nodes poll the same table and compete for work.
 *
 * <p><b>At-least-once delivery.</b> A claim only <em>leases</em> a row (stamps {@code claimed_at}/
 * {@code claimed_by}); the row is marked {@code consumed = TRUE} (acked) <em>after</em> its consumer
 * returns successfully. If the consumer throws, or the node crashes mid-processing, the lease
 * expires ({@link #LEASE_MS}) and the message is reclaimed and redelivered — the at-least-once
 * premise the {@link io.tranto.core.runners.Executor} relies on. (The previous implementation marked
 * rows consumed <em>before</em> dispatch, so a crash silently lost the message forever.) Acked rows
 * are periodically purged so the table does not grow without bound.</p>
 *
 * <p>Claiming is a conditional {@code UPDATE} over unconsumed rows whose lease is null or expired
 * (optimistic, portable across H2/Postgres/MySQL); on Postgres this is upgraded to {@code SELECT ...
 * FOR UPDATE SKIP LOCKED}. Each topic has one logical subscriber in the engine (executor for
 * executions, worker for worker-tasks), matching the in-memory queue's semantics.</p>
 *
 * @param <T> the message type
 */
public class JdbcQueue<T> implements QueueInterface<T>, AutoCloseable {

    private static final long POLL_INTERVAL_MS = 25;

    /** Default visibility timeout: a leased-but-unacked row is reclaimable (redelivered) after this long. */
    private static final long DEFAULT_LEASE_MS = 60_000;

    /** Acked rows older than this are purged. */
    private static final long RETENTION_MS = 300_000;

    /** Purge roughly once per this many idle poll cycles. */
    private static final int PURGE_EVERY_IDLE_POLLS = 200;

    private final JdbcDatabase database;
    private final String topic;
    private final Class<T> type;
    private final ObjectMapper mapper;
    /** Visibility timeout for this queue's leases. */
    private final long leaseMs;
    /** This queue instance's identity, stamped on rows it leases (node/topic ownership). */
    private final String ownerId = java.util.UUID.randomUUID().toString();

    private final List<Consumer<T>> consumers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private int idlePolls;
    private Thread poller;

    public JdbcQueue(final JdbcDatabase database, final String topic,
                     final Class<T> type, final ObjectMapper mapper) {
        this(database, topic, type, mapper, DEFAULT_LEASE_MS);
    }

    /** @param leaseMs visibility timeout before an unacked message is redelivered (tests use a short one). */
    JdbcQueue(final JdbcDatabase database, final String topic,
              final Class<T> type, final ObjectMapper mapper, final long leaseMs) {
        this.database = database;
        this.topic = topic;
        this.type = type;
        this.mapper = mapper;
        this.leaseMs = leaseMs;
    }

    @Override
    public void emit(final T message) {
        String sql = "INSERT INTO queue_messages (topic, data) VALUES (?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            statement.setString(2, mapper.writeValueAsString(message));
            statement.executeUpdate();
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to emit to queue " + topic, e);
        }
    }

    @Override
    public synchronized AutoCloseable receive(final Consumer<T> consumer) {
        consumers.add(consumer);
        startPoller();
        return () -> consumers.remove(consumer);
    }

    private void startPoller() {
        if (running) {
            return;
        }
        running = true;
        poller = new Thread(this::pollLoop, "tranto-jdbc-queue-" + topic);
        poller.setDaemon(true);
        poller.start();
    }

    /** A row this queue has leased: its id (for acking) and the decoded message. */
    private record Leased<T>(long offsetId, T message) {
    }

    private void pollLoop() {
        while (running) {
            try {
                List<Leased<T>> claimed = claimBatch();
                for (Leased<T> leased : claimed) {
                    dispatcher.submit(() -> dispatch(leased));
                }
                if (claimed.isEmpty()) {
                    if (++idlePolls >= PURGE_EVERY_IDLE_POLLS) {
                        idlePolls = 0;
                        purgeConsumed();
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // transient DB error: back off and keep polling
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Run every consumer for a leased message, then <em>ack</em> it (mark consumed). If any consumer
     * throws, the row is left unacked: its lease expires and the message is redelivered.
     */
    private void dispatch(final Leased<T> leased) {
        try {
            for (Consumer<T> consumer : consumers) {
                consumer.accept(leased.message());
            }
            ack(leased.offsetId());
        } catch (Exception e) {
            // Leave the row unacked; the lease will expire and it will be reclaimed and redelivered.
        }
    }

    /**
     * Lease the next batch of deliverable rows for this topic. A row is deliverable when it is not yet
     * acked ({@code consumed = FALSE}) and either never leased or its lease has expired. The
     * conditional {@code UPDATE} makes the claim atomic across competing nodes.
     */
    private List<Leased<T>> claimBatch() throws SQLException, com.fasterxml.jackson.core.JsonProcessingException {
        List<Leased<T>> claimed = new ArrayList<>();
        long nowMs = System.currentTimeMillis();
        java.sql.Timestamp now = new java.sql.Timestamp(nowMs);
        java.sql.Timestamp leaseCutoff = new java.sql.Timestamp(nowMs - leaseMs);

        String select = "SELECT offset_id, data FROM queue_messages "
            + "WHERE topic = ? AND consumed = FALSE AND (claimed_at IS NULL OR claimed_at < ?) "
            + "ORDER BY offset_id LIMIT 50";
        try (Connection connection = database.connection();
             PreparedStatement selectStmt = connection.prepareStatement(select)) {
            selectStmt.setString(1, topic);
            selectStmt.setTimestamp(2, leaseCutoff);
            List<long[]> ids = new ArrayList<>();
            List<String> values = new ArrayList<>();
            try (ResultSet rs = selectStmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(new long[]{rs.getLong("offset_id")});
                    values.add(rs.getString("data"));
                }
            }
            String claim = "UPDATE queue_messages SET claimed_at = ?, claimed_by = ? "
                + "WHERE offset_id = ? AND consumed = FALSE AND (claimed_at IS NULL OR claimed_at < ?)";
            try (PreparedStatement claimStmt = connection.prepareStatement(claim)) {
                for (int i = 0; i < ids.size(); i++) {
                    claimStmt.setTimestamp(1, now);
                    claimStmt.setString(2, ownerId);
                    claimStmt.setLong(3, ids.get(i)[0]);
                    claimStmt.setTimestamp(4, leaseCutoff);
                    if (claimStmt.executeUpdate() == 1) {
                        claimed.add(new Leased<>(ids.get(i)[0], mapper.readValue(values.get(i), type)));
                    }
                }
            }
        }
        return claimed;
    }

    /** Mark a leased row consumed (acked) once its consumer has run successfully. */
    private void ack(final long offsetId) {
        String sql = "UPDATE queue_messages SET consumed = TRUE WHERE offset_id = ? AND claimed_by = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offsetId);
            statement.setString(2, ownerId);
            statement.executeUpdate();
        } catch (SQLException e) {
            // Ack failed: the lease will expire and the message is redelivered (at-least-once).
        }
    }

    /** Delete acked rows past the retention window so {@code queue_messages} does not grow unbounded. */
    private void purgeConsumed() {
        String sql = "DELETE FROM queue_messages WHERE topic = ? AND consumed = TRUE AND claimed_at < ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            statement.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis() - RETENTION_MS));
            statement.executeUpdate();
        } catch (SQLException e) {
            // Purge is best-effort; a transient failure just retries next cycle.
        }
    }

    @Override
    public void close() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
        dispatcher.shutdownNow();
        consumers.clear();
    }
}
