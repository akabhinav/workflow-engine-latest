package io.tranto.core.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * A cluster-wide advisory lock backed by the {@code locks} table. A lock is a named lease: acquiring
 * it stamps the caller's {@code holder} id and an {@code expires_at}; the holder keeps it by
 * re-acquiring (renewal) and drops it with {@link #release}. If a holder crashes without releasing,
 * the lease simply expires and the lock becomes acquirable again — no stuck locks.
 *
 * <p>Two things build on this one primitive:</p>
 * <ul>
 *   <li>{@link io.tranto.core.runners.JdbcLockProvider} — per-execution mutual exclusion so two
 *       executor nodes cannot process the same execution concurrently (fixes the per-JVM-only lock).</li>
 *   <li>Scheduler leader election — exactly one node holds the {@code scheduler} lease and fires cron.</li>
 * </ul>
 *
 * <p>Acquisition is portable across H2/Postgres/MySQL: a conditional {@code UPDATE} takes over a row
 * that is free (lease expired) or already ours; otherwise an {@code INSERT} creates it, and a losing
 * race on the primary key simply means someone else holds it.</p>
 */
public class JdbcLock {

    private final JdbcDatabase database;

    public JdbcLock(final JdbcDatabase database) {
        this.database = database;
    }

    /**
     * Try to acquire (or renew) the named lock for {@code holder} for {@code ttlMs} milliseconds.
     *
     * @return true if the lock is now held by {@code holder}
     */
    public boolean tryAcquire(final String name, final String holder, final long ttlMs) {
        long nowMs = System.currentTimeMillis();
        Timestamp now = new Timestamp(nowMs);
        Timestamp expires = new Timestamp(nowMs + ttlMs);
        try (Connection connection = database.connection()) {
            // 1. Take over an existing row that is ours or whose lease has expired.
            String update = "UPDATE locks SET holder = ?, expires_at = ? "
                + "WHERE name = ? AND (holder = ? OR expires_at < ?)";
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, holder);
                statement.setTimestamp(2, expires);
                statement.setString(3, name);
                statement.setString(4, holder);
                statement.setTimestamp(5, now);
                if (statement.executeUpdate() == 1) {
                    return true;
                }
            }
            // 2. No takeable row: either it does not exist (create it) or it is held by someone else.
            String insert = "INSERT INTO locks (name, holder, expires_at) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, name);
                statement.setString(2, holder);
                statement.setTimestamp(3, expires);
                statement.executeUpdate();
                return true;
            } catch (SQLException insertRace) {
                // Primary-key conflict: another holder created/holds it. Not acquired.
                return false;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Release the named lock if {@code holder} still holds it. */
    public void release(final String name, final String holder) {
        String sql = "DELETE FROM locks WHERE name = ? AND holder = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, holder);
            statement.executeUpdate();
        } catch (SQLException e) {
            // Best-effort release; the lease will expire regardless.
        }
    }
}
