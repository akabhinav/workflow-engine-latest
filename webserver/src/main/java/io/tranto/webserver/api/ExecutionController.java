package io.tranto.webserver.api;

import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.webserver.api.dto.ExecutionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * Trigger and read executions. Mirrors Kestra's {@code /api/v1/{tenant}/executions} shape.
 * Triggering is non-blocking: the execution is returned immediately (state CREATED/RUNNING) and
 * the client polls {@code GET /{id}} to watch it progress to a terminal state.
 */
@RestController
@RequestMapping("/api/v1/executions")
public class ExecutionController {

    private final StandaloneEngine engine;

    public ExecutionController(final StandaloneEngine engine) {
        this.engine = engine;
    }

    /** Trigger an execution of a registered flow. */
    @PostMapping("/{namespace}/{id}")
    public ExecutionResponse trigger(@PathVariable final String namespace, @PathVariable final String id) {
        Flow flow = engine.flows().findById(null, namespace, id, null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Flow not found: " + namespace + "." + id + " (create it via POST /api/v1/flows)"));
        return ExecutionResponse.of(engine.submit(flow, Map.of()));
    }

    /** List all executions (production adds pagination + filters). */
    @GetMapping
    public java.util.List<ExecutionResponse> list() {
        return engine.executions().findAll().stream().map(ExecutionResponse::of).toList();
    }

    /** Read an execution by id (poll for state). */
    @GetMapping("/{id}")
    public ExecutionResponse get(@PathVariable final String id) {
        return engine.executions().findById(id)
            .map(ExecutionResponse::of)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"));
    }

    /**
     * Live-follow an execution via Server-Sent Events: emits the current state immediately, then one
     * event per state change, and completes the stream when the execution terminates.
     */
    @GetMapping("/{id}/follow")
    public SseEmitter follow(@PathVariable final String id) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; we complete on terminal state

        Execution current = engine.executions().findById(id).orElse(null);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        }
        send(emitter, current);
        if (current.getState().isTerminated()) {
            emitter.complete();
            return emitter;
        }

        AutoCloseable subscription = engine.subscribe(execution -> {
            if (!execution.getId().equals(id)) {
                return;
            }
            send(emitter, execution);
            if (execution.getState().isTerminated()) {
                emitter.complete();
            }
        });
        emitter.onCompletion(() -> closeQuietly(subscription));
        emitter.onError(t -> closeQuietly(subscription));
        return emitter;
    }

    private static void send(final SseEmitter emitter, final Execution execution) {
        try {
            emitter.send(SseEmitter.event()
                .name("execution")
                .data(ExecutionResponse.of(execution)));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private static void closeQuietly(final AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // best-effort unsubscribe
        }
    }
}
