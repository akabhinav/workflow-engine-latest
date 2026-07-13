package io.tranto.webserver.api;

import io.tranto.core.models.flows.Flow;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.webserver.api.dto.FlowResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Flow CRUD. Phase-1 surface: create from YAML and read a summary. Mirrors Kestra's
 * {@code /api/v1/{tenant}/flows} shape (single-tenant for now).
 */
@RestController
@RequestMapping("/api/v1/flows")
public class FlowController {

    private final YamlFlowParser parser;
    private final StandaloneEngine engine;

    public FlowController(final YamlFlowParser parser, final StandaloneEngine engine) {
        this.parser = parser;
        this.engine = engine;
    }

    /** Create (register) a flow from a YAML body. */
    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "application/x-yaml", "text/yaml"})
    public FlowResponse create(@RequestBody final String yaml) {
        try {
            Flow flow = parser.parse(yaml);
            return FlowResponse.of(engine.register(flow));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid flow: " + e.getMessage());
        }
    }

    /** List all registered flows. */
    @GetMapping
    public java.util.List<FlowResponse> list() {
        return engine.flows().findAll().stream().map(FlowResponse::of).toList();
    }

    /** Read a flow summary by namespace/id. */
    @GetMapping("/{namespace}/{id}")
    public FlowResponse get(@PathVariable final String namespace, @PathVariable final String id) {
        return engine.flows().findById(null, namespace, id, null)
            .map(FlowResponse::of)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Flow not found"));
    }
}
