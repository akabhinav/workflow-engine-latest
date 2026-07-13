package io.tranto.core.runners;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks per-flow concurrency slots: how many executions of a flow are RUNNING against its limit, and
 * which are QUEUED waiting for a slot. The standalone engine uses {@link InMemory} (per-JVM maps); the
 * distributed engine uses {@link io.tranto.core.jdbc.JdbcConcurrencyStore} so a {@code limit: 1} flow
 * runs one-at-a-time <em>across the whole cluster</em>, not once per node (Landmine #3).
 */
public interface ConcurrencyStore {

    /**
     * Atomically take a RUNNING slot for {@code executionId} if the flow has fewer than {@code limit}
     * running.
     *
     * @return true if a slot was taken (admit now); false if already at the limit
     */
    boolean tryAdmit(String flowKey, String executionId, int limit);

    /** Record {@code executionId} as QUEUED for {@code flowKey} (FIFO). */
    void enqueue(String flowKey, String executionId);

    /**
     * Release the slot {@code executionId} holds and promote the oldest QUEUED execution to RUNNING.
     *
     * @return the promoted execution id (to be dispatched), or null if nothing was promoted
     */
    String releaseAndPromote(String flowKey, String executionId);

    /** Per-JVM slot accounting (standalone engine). */
    class InMemory implements ConcurrencyStore {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, Integer> runningByFlow = new HashMap<>();
        private final Map<String, Deque<String>> queuedByFlow = new HashMap<>();
        private final ConcurrentHashMap<String, String> slotFlow = new ConcurrentHashMap<>();

        @Override
        public boolean tryAdmit(final String flowKey, final String executionId, final int limit) {
            lock.lock();
            try {
                int running = runningByFlow.getOrDefault(flowKey, 0);
                if (running < limit) {
                    runningByFlow.put(flowKey, running + 1);
                    slotFlow.put(executionId, flowKey);
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void enqueue(final String flowKey, final String executionId) {
            lock.lock();
            try {
                queuedByFlow.computeIfAbsent(flowKey, k -> new ArrayDeque<>()).add(executionId);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public String releaseAndPromote(final String flowKey, final String executionId) {
            lock.lock();
            try {
                String key = slotFlow.remove(executionId);
                if (key == null) {
                    return null; // held no running slot
                }
                int running = runningByFlow.getOrDefault(key, 1) - 1;
                String promoted = null;
                Deque<String> queue = queuedByFlow.get(key);
                if (queue != null && !queue.isEmpty()) {
                    promoted = queue.poll();
                    slotFlow.put(promoted, key);
                    running += 1; // the freed slot is immediately taken by the promoted execution
                }
                runningByFlow.put(key, Math.max(0, running));
                return promoted;
            } finally {
                lock.unlock();
            }
        }
    }
}
