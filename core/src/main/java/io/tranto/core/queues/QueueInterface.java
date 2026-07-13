package io.tranto.core.queues;

import java.util.function.Consumer;

/**
 * The messaging seam every service talks through. Kept deliberately tiny so backends are easy to
 * swap: the in-memory implementation ({@link MemoryQueue}) powers {@code server local}, and a
 * JDBC ({@code FOR UPDATE SKIP LOCKED}) implementation powers cluster mode — both behind this
 * one interface. See docs/06 §3.
 *
 * @param <T> the message type
 */
public interface QueueInterface<T> {

    /** Publish a message to the queue. */
    void emit(T message);

    /**
     * Subscribe a consumer to the queue.
     *
     * @param consumer invoked for each delivered message
     * @return a handle that unsubscribes when closed
     */
    AutoCloseable receive(Consumer<T> consumer);
}
