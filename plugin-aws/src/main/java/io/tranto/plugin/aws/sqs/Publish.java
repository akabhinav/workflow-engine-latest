package io.tranto.plugin.aws.sqs;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.runners.RunContext;
import io.tranto.plugin.aws.AbstractAwsTask;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Sends a message to an Amazon SQS queue.
 *
 * <pre>
 * - id: enqueue
 *   type: io.tranto.plugin.aws.sqs.Publish
 *   region: us-east-1
 *   queueUrl: "https://sqs.us-east-1.amazonaws.com/123456789012/jobs"
 *   message: "{{ outputs.build.artifact }}"
 *   delaySeconds: 5
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Send a message to an SQS queue")
public class Publish extends AbstractAwsTask implements RunnableTask<Publish.SendOutput> {

    /** The target SQS queue URL (dynamic). */
    @PluginProperty(dynamic = true)
    private String queueUrl;

    /** The message body (dynamic). */
    @PluginProperty(dynamic = true)
    private String message;

    /** Optional delivery delay in seconds (0–900). */
    @PluginProperty
    private Integer delaySeconds;

    /** Optional message group id (dynamic), required for FIFO queues. */
    @PluginProperty(dynamic = true)
    private String messageGroupId;

    @Override
    public SendOutput run(final RunContext runContext) throws Exception {
        SendMessageRequest.Builder request = SendMessageRequest.builder()
            .queueUrl(runContext.render(queueUrl))
            .messageBody(runContext.render(message));
        if (delaySeconds != null) {
            request.delaySeconds(delaySeconds);
        }
        if (messageGroupId != null && !messageGroupId.isBlank()) {
            request.messageGroupId(runContext.render(messageGroupId));
        }
        try (SqsClient client = client(runContext)) {
            SendMessageResponse response = client.sendMessage(request.build());
            runContext.logger().info("Sent SQS message {} to {}", response.messageId(), queueUrl);
            return new SendOutput(response.messageId(), response.sequenceNumber(), response.md5OfMessageBody());
        }
    }

    /** The SQS client — overridable so unit tests can inject a mock. */
    protected SqsClient client(final RunContext runContext) throws Exception {
        return configure(SqsClient.builder(), runContext).build();
    }

    /**
     * @param messageId      the id SQS assigned to the message
     * @param sequenceNumber the FIFO sequence number (null for standard queues)
     * @param md5OfBody      the MD5 digest SQS computed of the message body
     */
    public record SendOutput(String messageId, String sequenceNumber, String md5OfBody) implements Output {
    }
}
