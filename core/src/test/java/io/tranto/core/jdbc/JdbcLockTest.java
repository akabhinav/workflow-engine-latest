package io.tranto.core.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cluster-wide lock primitive that per-execution serialisation (Landmine #2) and scheduler leader
 * election (Landmine #4) both build on: mutual exclusion between holders, release, lease expiry on a
 * crashed holder, and re-entrant renewal by the current holder.
 */
class JdbcLockTest {

    @Test
    void oneHolderExcludesAnother_untilReleased() {
        JdbcDatabase db = JdbcDatabase.h2InMemory("lock_exclude");
        JdbcLock lock = new JdbcLock(db);

        assertThat(lock.tryAcquire("job", "node-A", 60_000)).isTrue();
        // A holds it: B cannot take it.
        assertThat(lock.tryAcquire("job", "node-B", 60_000)).isFalse();
        // A can renew (re-entrant for the same holder).
        assertThat(lock.tryAcquire("job", "node-A", 60_000)).isTrue();

        lock.release("job", "node-A");
        // Now B can take it.
        assertThat(lock.tryAcquire("job", "node-B", 60_000)).isTrue();
    }

    @Test
    void expiredLeaseBecomesAcquirable_evenWithoutRelease() throws Exception {
        JdbcDatabase db = JdbcDatabase.h2InMemory("lock_expire");
        JdbcLock lock = new JdbcLock(db);

        // A holds it with a very short lease and then "crashes" (never releases).
        assertThat(lock.tryAcquire("job", "node-A", 100)).isTrue();
        assertThat(lock.tryAcquire("job", "node-B", 60_000)).isFalse();

        Thread.sleep(150); // lease expires
        // B reclaims the expired lease — no stuck lock after a crash.
        assertThat(lock.tryAcquire("job", "node-B", 60_000)).isTrue();
    }
}
