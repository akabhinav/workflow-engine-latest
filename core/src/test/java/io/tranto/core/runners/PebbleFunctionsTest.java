package io.tranto.core.runners;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tranto's custom Pebble functions: now, uuid, json, fromJson. */
class PebbleFunctionsTest {

    private final VariableRenderer renderer = new VariableRenderer();

    @Test
    void nowReturnsAnIsoTimestamp() throws Exception {
        String rendered = renderer.render("{{ now() }}", Map.of());
        assertThat(rendered).contains("T").contains("Z"); // ISO-8601 instant
    }

    @Test
    void uuidReturnsAUuid() throws Exception {
        String rendered = renderer.render("{{ uuid() }}", Map.of());
        assertThat(rendered).matches("[0-9a-f-]{36}");
    }

    @Test
    void fromJsonParsesAndIndexes() throws Exception {
        String rendered = renderer.render("{{ fromJson('{\"a\": 42}').a }}", Map.of());
        assertThat(rendered).isEqualTo("42");
    }

    @Test
    void jsonSerializesAValue() throws Exception {
        String rendered = renderer.render("{{ json(payload) }}", Map.of("payload", Map.of("k", "v")));
        assertThat(rendered).isEqualTo("{\"k\":\"v\"}");
    }
}
