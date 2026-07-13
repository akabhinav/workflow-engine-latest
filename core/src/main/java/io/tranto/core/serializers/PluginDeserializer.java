package io.tranto.core.serializers;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.models.Plugin;
import io.tranto.core.plugins.PluginRegistry;

import java.io.IOException;

/**
 * Deserializes a polymorphic plugin (e.g. a {@code Task}) by reading its {@code type} field and
 * resolving the concrete class from the {@link PluginRegistry} at runtime — rather than a static
 * {@code @JsonSubTypes} list. This is what lets external plugin JARs contribute new types.
 *
 * <p>Bound to the abstract base type (e.g. {@code Task.class}) via {@link PluginModule}. Once the
 * concrete class is known, deserialization delegates to Jackson's default bean deserializer for
 * that class, so there is no recursion.</p>
 *
 * @param <T> the abstract plugin base type this deserializer handles
 */
public class PluginDeserializer<T extends Plugin> extends JsonDeserializer<T> {

    private final PluginRegistry registry;

    public PluginDeserializer(final PluginRegistry registry) {
        this.registry = registry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T deserialize(final JsonParser parser, final DeserializationContext context) throws IOException {
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        JsonNode node = mapper.readTree(parser);

        JsonNode typeNode = node.get("type");
        if (typeNode == null || typeNode.isNull() || typeNode.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required 'type' on plugin: " + node);
        }
        String type = typeNode.asText();

        Class<? extends Plugin> concrete = registry.findByType(type)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown plugin type '" + type + "'. Is the plugin registered/on the classpath?"));

        return (T) mapper.treeToValue(node, concrete);
    }
}
