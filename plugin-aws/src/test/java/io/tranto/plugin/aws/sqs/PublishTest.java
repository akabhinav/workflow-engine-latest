package io.tranto.plugin.aws.sqs;

import io.tranto.core.runners.RunContext;
import io.tranto.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit-tests the SQS Publish task against a mocked client: request building + response mapping. */
class PublishTest {

    private final RunContext runContext = new RunContextFactory().of(Map.of());

    @Test
    void sendsMessageWithDelayAndMapsResponse() throws Exception {
        Publish task = Publish.builder()
            .id("enqueue").type("io.tranto.plugin.aws.sqs.Publish")
            .queueUrl("https://sqs.us-east-1.amazonaws.com/123/jobs")
            .message("payload")
            .delaySeconds(5)
            .build();

        SqsClient client = mock(SqsClient.class);
        when(client.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(SendMessageResponse.builder()
                .messageId("m-1").sequenceNumber("9").md5OfMessageBody("abc").build());

        Publish spy = spy(task);
        doReturn(client).when(spy).client(any());

        Publish.SendOutput out = spy.run(runContext);

        assertThat(out.messageId()).isEqualTo("m-1");
        assertThat(out.md5OfBody()).isEqualTo("abc");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(client).sendMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/123/jobs");
        assertThat(captor.getValue().messageBody()).isEqualTo("payload");
        assertThat(captor.getValue().delaySeconds()).isEqualTo(5);
    }
}
