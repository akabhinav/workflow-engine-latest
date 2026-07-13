package io.tranto.core.jdbc;

import io.tranto.core.runners.ConcurrencyStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Cluster-wide {@link ConcurrencyStore}: RUNNING/QUEUED slots are rows in the shared
 * {@code concurrency_slots} table. Every admission/release decision for a flow is serialised across
 * nodes with a {@link JdbcLock} keyed on the flow, so a {@code limit: N} flow admits at most N
 * executions cluster-wide (fixes "a limit-1 flow runs once per node"). QUEUED rows are promoted in
 * FIFO order by their identity {@code seq}.
 */
public class JdbcConcurrencyStore implements ConcurrencyStore {

    private static final long LEASE_MS = 10_000;
    private static final long RETRY_MS = 15;

    private final JdbcDatabase database;
    private final JdbcLock lock;
    private final String holderId;

    public JdbcConcurrencyStore(final JdbcDatabase database, final JdbcLock lock, final String holderId) {
        this.database = database;
        this.lock = lock;
        this.holderId = holderId;
    }

    @Override
    public boolean tryAdmit(final String flowKey, final String executionId, final int limit) {
        return underLock(flowKey, () -> {
            try (Connection connection = database.connection()) {
                int running = runningCount(connection, flowKey);
                if (running >= limit) {
                    return false;
                }
                insertSlot(connection, flowKey, executionId, "RUNNING");
                return true;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to admit " + executionId, e);
            }
        });
    }

    @Override
    public void enqueue(final String flowKey, final String executionId) {
        underLock(flowKey, () -> {
            try (Connection connection = database.connection()) {
                insertSlot(connection, flowKey, executionId, "QUEUED");
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to enqueue " + executionId, e);
            }
        });
    }

    @Override
    public String releaseAndPromote(final String flowKey, final String executionId) {
        return underLock(flowKey, () -> {
            try (Connection connection = database.connection()) {
                int freedRunning = delete(connection, executionId, "RUNNING");
                delete(connection, executionId, "QUEUED"); // clean up if it was waiting
                if (freedRunning == 0) {
                    return null; // held no running slot — nothing to promote
                }
                return promoteOldestQueued(connection, flowKey);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to release " + executionId, e);
            }
        });
    }

    private int runningCount(final Connection connection, final String flowKey) throws SQLException {
        String sql = "SELECT COUNT(*) FROM concurrency_slots WHERE flow_key = ? AND status = 'RUNNING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowKey);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertSlot(final Connection connection, final String flowKey,
                            final String executionId, final String status) throws SQLException {
        String sql = "INSERT INTO concurrency_slots (flow_key, execution_id, status) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, flowKey);
            statement.setString(2, executionId);
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }

    private int delete(final Connection connection, final String executionId, final String status) throws SQLException {
        String sql = "DELETE FROM concurrency_slots WHERE execution_id = ? AND status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            statement.setString(2, status);
            return statement.executeUpdate();
        }
    }

    /** Promote the oldest QUEUED row for the flow to RUNNING; @return its execution id or null. */
    private String promoteOldestQueued(final Connection connection, final String flowKey) throws SQLException {
        String select = "SELECT seq, execution_id FROM concurrency_slots "
            + "WHERE flow_key = ? AND status = 'QUEUED' ORDER BY seq LIMIT 1";
        long seq;
        String executionId;
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, flowKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                seq = rs.getLong("seq");
                executionId = rs.getString("execution_id");
            }
        }
        String promote = "UPDATE concurrency_slots SET status = 'RUNNING' WHERE seq = ?";
        try (PreparedStatement statement = connection.prepareStatement(promote)) {
            statement.setLong(1, seq);
            statement.executeUpdate();
        }
        return executionId;
    }

    /** Run {@code work} while holding the per-flow admission lock (spin-acquire; lease covers crashes). */
    private <T> T underLock(final String flowKey, final java.util.function.Supplier<T> work) {
        String lockName = "concurrency:" + flowKey;
        while (!lock.tryAcquire(lockName, holderId, LEASE_MS)) {
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            return work.get();
        } finally {
            lock.release(lockName, holderId);
        }
    }
}
