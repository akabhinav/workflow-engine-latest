package io.tranto.plugin.aws.sns;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.runners.RunContext;
import io.tranto.plugin.aws.AbstractAwsTask;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Publishes a message to an Amazon SNS topic.
 *
 * <pre>
 * - id: notify
 *   type: io.tranto.plugin.aws.sns.Publish
 *   region: us-east-1
 *   topicArn: "arn:aws:sns:us-east-1:123456789012:alerts"
 *   subject: "Build {{ inputs.build }}"
 *   message: "Deployment finished with status {{ outputs.deploy.status }}"
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Publish a message to an SNS topic")
public class Publish extends AbstractAwsTask implements RunnableTask<Publish.PublishOutput> {

    /** The ARN of the target SNS topic (dynamic). */
    @PluginProperty(dynamic = true)
    private String topicArn;

    /** The message body (dynamic). */
    @PluginProperty(dynamic = true)
    private String message;

    /** Optional subject line (dynamic), used for email-subscribed topics. */
    @PluginProperty(dynamic = true)
    private String subject;

    @Override
    public PublishOutput run(final RunContext runContext) throws Exception {
        PublishRequest.Builder request = PublishRequest.builder()
            .topicArn(runContext.render(topicArn))
            .message(runContext.render(message));
        if (subject != null && !subject.isBlank()) {
            request.subject(runContext.render(subject));
        }
        try (SnsClient client = client(runContext)) {
            PublishResponse response = client.publish(request.build());
            runContext.logger().info("Published SNS message {} to {}", response.messageId(), topicArn);
            return new PublishOutput(response.messageId(), response.sequenceNumber());
        }
    }

    /** The SNS client — overridable so unit tests can inject a mock. */
    protected SnsClient client(final RunContext runContext) throws Exception {
        return configure(SnsClient.builder(), runContext).build();
    }

    /**
     * @param messageId      the id SNS assigned to the published message
     * @param sequenceNumber the FIFO sequence number (null for standard topics)
     */
    public record PublishOutput(String messageId, String sequenceNumber) implements Output {
    }
}
