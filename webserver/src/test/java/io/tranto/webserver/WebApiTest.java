package io.tranto.webserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real REST API over HTTP (MockMvc): health, create/list flows, trigger/list/poll
 * executions. Exercises the actual Spring wiring end-to-end — the web layer is now tested, not just
 * hand-verified with curl.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    private static final String FLOW = """
        id: web_hello
        namespace: dev
        tasks:
          - id: greet
            type: io.tranto.plugin.core.log.Log
            message: "hello from the api"
        """;

    @Autowired
    private MockMvc mvc;

    @Test
    void fullApiRoundTrip() throws Exception {
        // Health
        mvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        // Create a flow from YAML
        mvc.perform(post("/api/v1/flows").contentType(MediaType.TEXT_PLAIN).content(FLOW))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.namespace").value("dev"))
            .andExpect(jsonPath("$.id").value("web_hello"));

        // List flows
        mvc.perform(get("/api/v1/flows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='web_hello')]").isNotEmpty());

        // Trigger an execution
        MvcResult triggered = mvc.perform(post("/api/v1/executions/dev/web_hello"))
            .andExpect(status().isOk())
            .andReturn();
        String executionId = com.jayway.jsonpath.JsonPath.read(
            triggered.getResponse().getContentAsString(), "$.id");
        assertThat(executionId).isNotBlank();

        // List executions
        mvc.perform(get("/api/v1/executions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());

        // Poll the execution until it reaches SUCCESS
        String state = null;
        for (int i = 0; i < 50; i++) {
            MvcResult polled = mvc.perform(get("/api/v1/executions/" + executionId))
                .andExpect(status().isOk()).andReturn();
            state = com.jayway.jsonpath.JsonPath.read(polled.getResponse().getContentAsString(), "$.state");
            if ("SUCCESS".equals(state)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(state).isEqualTo("SUCCESS");
    }
}
