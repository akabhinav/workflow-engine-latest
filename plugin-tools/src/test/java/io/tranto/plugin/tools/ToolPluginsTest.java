package io.tranto.plugin.tools;

import io.tranto.core.models.execution.StateType;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.models.executions.TaskRun;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.plugins.PluginRegistry;
import io.tranto.core.plugins.SimplePluginRegistry;
import io.tranto.core.runners.StandaloneEngine;
import io.tranto.core.serializers.JacksonMapper;
import io.tranto.core.serializers.YamlFlowParser;
import io.tranto.plugin.core.CorePlugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the ten "tools" plugins: each flow is parsed and executed on the real
 * {@link StandaloneEngine}, then the task's typed output is asserted. This proves the plugins are
 * discoverable, Pebble-rendered, and behave correctly on the actual engine — not just in isolation.
 */
class ToolPluginsTest {

    private final YamlFlowParser parser;

    ToolPluginsTest() {
        PluginRegistry registry = new SimplePluginRegistry();
        CorePlugins.all().forEach(registry::register);
        ToolPlugins.all().forEach(registry::register);
        this.parser = new YamlFlowParser(new JacksonMapper(registry));
    }

    private Execution run(String yaml) throws Exception {
        return run(yaml, Map.of());
    }

    private Execution run(String yaml, Map<String, Object> inputs) throws Exception {
        Flow flow = parser.parse(yaml);
        try (StandaloneEngine engine = new StandaloneEngine()) {
            return engine.run(flow, inputs, Duration.ofSeconds(20));
        }
    }

    private static String outputs(Execution execution, String taskId) {
        TaskRun taskRun = execution.findTaskRunByTaskId(taskId).orElseThrow();
        return String.valueOf(taskRun.getOutputs());
    }

    // ---- crypto/Hash --------------------------------------------------------

    @Test
    void hashComputesSha256() throws Exception {
        Execution execution = run("""
            id: hash_flow
            namespace: dev
            tasks:
              - id: h
                type: io.tranto.plugin.tools.crypto.Hash
                from: abc
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        // Known SHA-256("abc").
        assertThat(outputs(execution, "h"))
            .contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            .contains("SHA-256");
    }

    @Test
    void hashSupportsMd5AndUppercase() throws Exception {
        Execution execution = run("""
            id: hash_md5
            namespace: dev
            tasks:
              - id: h
                type: io.tranto.plugin.tools.crypto.Hash
                from: abc
                algorithm: MD5
                uppercase: true
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        // MD5("abc") uppercased.
        assertThat(outputs(execution, "h")).contains("900150983CD24FB0D6963F7D28E17F72");
    }

    // ---- encoding/Base64 ----------------------------------------------------

    @Test
    void base64RoundTrip() throws Exception {
        Execution execution = run("""
            id: b64
            namespace: dev
            tasks:
              - id: enc
                type: io.tranto.plugin.tools.encoding.Base64Encode
                from: "hello world"
              - id: dec
                type: io.tranto.plugin.tools.encoding.Base64Decode
                from: "{{ outputs.enc.encoded }}"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(outputs(execution, "enc")).contains("aGVsbG8gd29ybGQ=");
        assertThat(outputs(execution, "dec")).contains("hello world");
    }

    // ---- json/Parse ---------------------------------------------------------

    @Test
    void jsonParseExposesStructuredValue() throws Exception {
        Execution execution = run("""
            id: json_flow
            namespace: dev
            tasks:
              - id: p
                type: io.tranto.plugin.tools.json.Parse
                from: '{"name":"ada","tags":["a","b"]}'
              - id: use
                type: io.tranto.plugin.core.debug.Return
                format: "{{ outputs.p.value.name }}-{{ outputs.p.size }}"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(outputs(execution, "use")).contains("ada-2");
    }

    @Test
    void jsonParseFailsOnMalformedInput() throws Exception {
        Execution execution = run("""
            id: json_bad
            namespace: dev
            tasks:
              - id: p
                type: io.tranto.plugin.tools.json.Parse
                from: "{not json"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
    }

    // ---- csv/Read -----------------------------------------------------------

    @Test
    void csvReadParsesHeaderKeyedRows() throws Exception {
        Execution execution = run("""
            id: csv_flow
            namespace: dev
            tasks:
              - id: c
                type: io.tranto.plugin.tools.csv.Read
                from: |
                  name,city
                  ada,"London, UK"
                  bob,Paris
              - id: use
                type: io.tranto.plugin.core.debug.Return
                format: "{{ outputs.c.count }}|{{ outputs.c.rows[0].city }}"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        // Quoted field keeps its embedded comma; two data rows.
        assertThat(outputs(execution, "use")).contains("2|London, UK");
    }

    // ---- text/RegexExtract --------------------------------------------------

    @Test
    void regexExtractPullsGroups() throws Exception {
        Execution execution = run("""
            id: rx_flow
            namespace: dev
            tasks:
              - id: rx
                type: io.tranto.plugin.tools.text.RegexExtract
                from: "order-123 and order-456"
                pattern: "order-(\\\\d+)"
              - id: use
                type: io.tranto.plugin.core.debug.Return
                format: "{{ outputs.rx.first }}|{{ outputs.rx.groups[1] }}|{{ outputs.rx.all | length }}"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(outputs(execution, "use")).contains("order-123|123|2");
    }

    // ---- text/Replace -------------------------------------------------------

    @Test
    void replaceMasksAllMatches() throws Exception {
        Execution execution = run("""
            id: rep_flow
            namespace: dev
            tasks:
              - id: r
                type: io.tranto.plugin.tools.text.Replace
                from: "call 1234567890123 or 9876543210987"
                pattern: "\\\\d{13,16}"
                replacement: "****"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(outputs(execution, "r"))
            .contains("call **** or ****")
            .contains("count=2");
    }

    // ---- file/Write + file/Read --------------------------------------------

    @Test
    void fileWriteThenRead(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("nested/report.txt");
        Execution execution = run("""
            id: file_flow
            namespace: dev
            tasks:
              - id: w
                type: io.tranto.plugin.tools.file.Write
                path: "%s"
                content: "line for {{ flow.id }}"
              - id: r
                type: io.tranto.plugin.tools.file.Read
                path: "{{ outputs.w.path }}"
            """.formatted(target.toString().replace("\\", "\\\\")));
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(Files.readString(target)).isEqualTo("line for file_flow");
        assertThat(outputs(execution, "r")).contains("line for file_flow");
    }

    @Test
    void fileReadFailsForMissingFile(@TempDir Path tmp) throws Exception {
        Execution execution = run("""
            id: file_missing
            namespace: dev
            tasks:
              - id: r
                type: io.tranto.plugin.tools.file.Read
                path: "%s"
            """.formatted(tmp.resolve("nope.txt").toString().replace("\\", "\\\\")));
        assertThat(execution.getState().current()).isEqualTo(StateType.FAILED);
    }

    // ---- datetime/Format ----------------------------------------------------

    @Test
    void dateFormatParsesShiftsAndFormats() throws Exception {
        Execution execution = run("""
            id: date_flow
            namespace: dev
            tasks:
              - id: d
                type: io.tranto.plugin.tools.datetime.Format
                from: "2026-01-01T00:00:00Z"
                offset: "P1D"
                zone: "UTC"
                format: "yyyy-MM-dd"
            """);
        assertThat(execution.getState().current()).isEqualTo(StateType.SUCCESS);
        assertThat(outputs(execution, "d"))
            .contains("2026-01-02")
            .contains("epochSecond=1767312000");
    }
}
