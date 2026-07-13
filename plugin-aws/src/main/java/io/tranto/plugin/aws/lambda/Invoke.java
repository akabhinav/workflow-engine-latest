package io.tranto.plugin.aws.lambda;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.runners.RunContext;
import io.tranto.plugin.aws.AbstractAwsTask;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Invokes an AWS Lambda function and returns its response payload.
 *
 * <pre>
 * - id: score
 *   type: io.tranto.plugin.aws.lambda.Invoke
 *   region: us-east-1
 *   functionName: risk-scorer
 *   payload: '{"customer": "{{ inputs.id }}"}'
 * </pre>
 *
 * <p>Defaults to synchronous {@code RequestResponse} invocation. A Lambda that reports a function
 * error fails the task unless {@code allowFailure} is set (inspect {@code functionError} downstream).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Invoke a Lambda function")
public class Invoke extends AbstractAwsTask implements RunnableTask<Invoke.InvokeOutput> {

    /** The Lambda function name or ARN (dynamic). */
    @PluginProperty(dynamic = true)
    private String functionName;

    /** Optional JSON payload sent to the function (dynamic). */
    @PluginProperty(dynamic = true)
    private String payload;

    /** Invocation type: {@code RequestResponse} (default), {@code Event}, or {@code DryRun}. */
    @PluginProperty
    private String invocationType;

    @Override
    public InvokeOutput run(final RunContext runContext) throws Exception {
        InvokeRequest.Builder request = InvokeRequest.builder()
            .functionName(runContext.render(functionName))
            .invocationType(invocationType != null ? invocationType : "RequestResponse");
        if (payload != null && !payload.isBlank()) {
            request.payload(SdkBytes.fromUtf8String(runContext.render(payload)));
        }
        try (LambdaClient client = client(runContext)) {
            InvokeResponse response = client.invoke(request.build());
            String responsePayload = response.payload() != null ? response.payload().asUtf8String() : null;
            if (response.functionError() != null) {
                runContext.logger().error("Lambda {} returned a function error: {}",
                    functionName, response.functionError());
                throw new RuntimeException("Lambda function error: " + response.functionError()
                    + (responsePayload == null ? "" : " — " + responsePayload));
            }
            return new InvokeOutput(response.statusCode(), responsePayload, response.functionError());
        }
    }

    /** The Lambda client — overridable so unit tests can inject a mock. */
    protected LambdaClient client(final RunContext runContext) throws Exception {
        return configure(LambdaClient.builder(), runContext).build();
    }

    /**
     * @param statusCode    the HTTP status of the invocation (200/202/204)
     * @param payload       the function's response payload (UTF-8), or null
     * @param functionError the function error type if the function failed, else null
     */
    public record InvokeOutput(int statusCode, String payload, String functionError) implements Output {
    }
}
