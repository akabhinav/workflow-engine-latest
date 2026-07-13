package io.tranto.core.serializers;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.tranto.core.models.flows.Flow;

/**
 * Parses flow definitions (YAML) into the {@link Flow} model and serializes them back.
 * Thin wrapper over the shared YAML {@link com.fasterxml.jackson.databind.ObjectMapper} so the
 * rest of the engine has one obvious entry point for flow (de)serialization.
 */
public final class YamlFlowParser {

    private final JacksonMapper mapper;

    public YamlFlowParser(final JacksonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parse a YAML flow definition into a {@link Flow}.
     *
     * @param yaml the flow source
     * @return the parsed flow
     * @throws JsonProcessingException if the YAML is malformed or references an unknown plugin type
     */
    public Flow parse(final String yaml) throws JsonProcessingException {
        return mapper.yaml().readValue(yaml, Flow.class);
    }

    /**
     * Serialize a {@link Flow} back to YAML.
     */
    public String toYaml(final Flow flow) throws JsonProcessingException {
        return mapper.yaml().writeValueAsString(flow);
    }
}
