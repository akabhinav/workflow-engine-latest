package io.tranto.core.serializers;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.tranto.core.plugins.PluginRegistry;

/**
 * Central factory for the JSON and YAML {@link ObjectMapper}s used across the engine.
 *
 * <p>Every mapper is configured consistently: field-based access (so Lombok POJOs and records
 * (de)serialize without setters), ISO date/times (not epoch numbers), null values omitted, and
 * the {@link PluginModule} so polymorphic tasks resolve by {@code type}. An instance is bound to
 * one {@link PluginRegistry} because plugin resolution needs it.</p>
 */
public final class JacksonMapper {

    private final ObjectMapper json;
    private final ObjectMapper yaml;

    public JacksonMapper(final PluginRegistry registry) {
        this.json = configure(new ObjectMapper(), registry);
        this.yaml = configure(new ObjectMapper(yamlFactory()), registry);
    }

    /** @return the shared JSON mapper. */
    public ObjectMapper json() {
        return json;
    }

    /** @return the shared YAML mapper. */
    public ObjectMapper yaml() {
        return yaml;
    }

    private static YAMLFactory yamlFactory() {
        return YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();
    }

    private static ObjectMapper configure(final ObjectMapper mapper, final PluginRegistry registry) {
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(new ParameterNamesModule());
        mapper.registerModule(new PluginModule(registry));

        // Access fields directly; ignore getters/setters for (de)serialization symmetry.
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.CREATOR, Visibility.ANY);

        mapper.setSerializationInclusion(Include.NON_NULL);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return mapper;
    }
}
