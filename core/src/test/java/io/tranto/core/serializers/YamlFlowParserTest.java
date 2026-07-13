package io.tranto.core.serializers;

import io.tranto.core.fixtures.LogFixture;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlFlowParserTest {

    private YamlFlowParser parser;

    @BeforeEach
    void setUp() {
        PluginRegistry registry = new SimplePluginRegistry();
        registry.register(LogFixture.class);
        parser = new YamlFlowParser(new JacksonMapper(registry));
    }

    @Test
    void shouldParseFlowAndResolvePolymorphicTaskByType() throws Exception {
        // Given
        String yaml = """
            id: hello_world
            namespace: dev
            tasks:
              - id: say_hello
                type: io.tranto.core.fixtures.LogFixture
                message: "Hello, World!"
            """;

        // When
        Flow flow = parser.parse(yaml);

        // Then
        assertThat(flow.getId()).isEqualTo("hello_world");
        assertThat(flow.getNamespace()).isEqualTo("dev");
        assertThat(flow.getTasks()).hasSize(1);

        Task task = flow.getTasks().get(0);
        assertThat(task).isInstanceOf(LogFixture.class);
        assertThat(task.getId()).isEqualTo("say_hello");
        assertThat(((LogFixture) task).getMessage()).isEqualTo("Hello, World!");
    }

    @Test
    void shouldRoundTripFlowThroughYaml() throws Exception {
        // Given
        String yaml = """
            id: hello_world
            namespace: dev
            tasks:
              - id: say_hello
                type: io.tranto.core.fixtures.LogFixture
                message: "Hello, World!"
            """;

        // When: parse -> serialize -> parse again
        Flow first = parser.parse(yaml);
        String serialized = parser.toYaml(first);
        Flow second = parser.parse(serialized);

        // Then: the re-parsed flow is structurally identical
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getNamespace()).isEqualTo(first.getNamespace());
        assertThat(second.getTasks()).hasSize(1);
        assertThat(second.getTasks().get(0)).isInstanceOf(LogFixture.class);
        assertThat(((LogFixture) second.getTasks().get(0)).getMessage()).isEqualTo("Hello, World!");
        // The serialized form must carry the discriminating 'type'
        assertThat(serialized).contains("type: io.tranto.core.fixtures.LogFixture");
    }
}
