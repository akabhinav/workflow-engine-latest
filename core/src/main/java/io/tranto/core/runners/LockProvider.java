package io.tranto.core.runners;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises work on a keyed resource (an execution id) while {@code action} runs. The
 * {@link Executor} drives every mutation of a single execution under this lock — the correctness
 * primitive that makes at-least-once delivery safe by ensuring one execution is processed one step
 * at a time.
 *
 * <p>The standalone engine uses {@link InMemory} (a per-JVM {@link ReentrantLock} per key). The
 * distributed engine uses {@link JdbcLockProvider}, which additionally takes a cluster-wide DB lock
 * so two executor <em>nodes</em> cannot process the same execution concurrently.</p>
 */
public interface LockProvider {

    /** Run {@code action} while holding the lock for {@code key}; other holders of {@code key} wait. */
    void withLock(String key, Runnable action);

    /** Per-JVM locking: a {@link ReentrantLock} per key. Correct within one process. */
    class InMemory implements LockProvider {
        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        @Override
        public void withLock(final String key, final Runnable action) {
            ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            try {
                action.run();
            } finally {
                lock.unlock();
            }
        }
    }
}
