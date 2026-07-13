package io.tranto.webserver.api;

import io.tranto.core.runners.StandaloneEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes the engine's execution counters ({@code SUCCESS}/{@code FAILED}/… + {@code total}).
 * A lightweight built-in metric surface; a fuller Micrometer/OpenTelemetry export is a later step.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final StandaloneEngine engine;

    public MetricsController(final StandaloneEngine engine) {
        this.engine = engine;
    }

    @GetMapping
    public Map<String, Long> metrics() {
        return engine.metrics();
    }
}
