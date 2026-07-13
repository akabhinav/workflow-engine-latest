package io.tranto.plugin.aws.lambda;

import io.tranto.core.runners.RunContext;
import io.tranto.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Unit-tests the Lambda Invoke task against a mocked client: success mapping and function-error failure. */
class InvokeTest {

    private final RunContext runContext = new RunContextFactory().of(Map.of());

    @Test
    void invokesAndReturnsPayload() throws Exception {
        Invoke task = Invoke.builder()
            .id("score").type("io.tranto.plugin.aws.lambda.Invoke")
            .functionName("risk-scorer")
            .payload("{\"id\":1}")
            .build();

        LambdaClient client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class)))
            .thenReturn(InvokeResponse.builder()
                .statusCode(200).payload(SdkBytes.fromUtf8String("{\"score\":42}")).build());

        Invoke spy = spy(task);
        doReturn(client).when(spy).client(any());

        Invoke.InvokeOutput out = spy.run(runContext);

        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(out.payload()).contains("\"score\":42");
        assertThat(out.functionError()).isNull();
    }

    @Test
    void functionErrorFailsTheTask() throws Exception {
        Invoke task = Invoke.builder()
            .id("score").type("io.tranto.plugin.aws.lambda.Invoke")
            .functionName("risk-scorer")
            .build();

        LambdaClient client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class)))
            .thenReturn(InvokeResponse.builder()
                .statusCode(200).functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String("{\"errorMessage\":\"boom\"}")).build());

        Invoke spy = spy(task);
        doReturn(client).when(spy).client(any());

        assertThatThrownBy(() -> spy.run(runContext))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unhandled");
    }
}
