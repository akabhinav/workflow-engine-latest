package io.tranto.core.queues;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * In-memory {@link QueueInterface} for {@code server local} / tests. Messages are dispatched to
 * subscribers asynchronously on virtual threads, mimicking the real asynchronous queue so the
 * executor/worker code is written the same way regardless of backend.
 *
 * <p>Each queue instance is a single logical topic; wire exactly one consumer per work-queue
 * topic to get competing-consumer semantics. (The JDBC backend adds true multi-node competing
 * consumption via {@code SKIP LOCKED}.)</p>
 *
 * @param <T> the message type
 */
public class MemoryQueue<T> implements QueueInterface<T>, AutoCloseable {

    private final List<Consumer<T>> consumers = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void emit(final T message) {
        for (Consumer<T> consumer : consumers) {
            dispatcher.submit(() -> consumer.accept(message));
        }
    }

    @Override
    public AutoCloseable receive(final Consumer<T> consumer) {
        consumers.add(consumer);
        return () -> consumers.remove(consumer);
    }

    @Override
    public void close() {
        dispatcher.shutdownNow();
        consumers.clear();
    }
}
