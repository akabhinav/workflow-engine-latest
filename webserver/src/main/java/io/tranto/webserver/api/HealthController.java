package io.tranto.webserver.api;

import io.tranto.core.runners.StandaloneEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness/summary endpoint: reports the server is up plus quick counts of registered flows and
 * executions. Enough for a load balancer probe and a glance at what the node is holding.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final StandaloneEngine engine;

    public HealthController(final StandaloneEngine engine) {
        this.engine = engine;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("flows", engine.flows().findAll().size());
        body.put("executions", engine.executions().findAll().size());
        return body;
    }
}
