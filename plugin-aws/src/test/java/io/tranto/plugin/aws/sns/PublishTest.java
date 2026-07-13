package io.tranto.plugin.aws.sns;

import io.tranto.core.runners.RunContext;
import io.tranto.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the SNS Publish task against a mocked client (no live AWS): it renders the topic/message,
 * builds the right request, and maps the response to outputs.
 */
class PublishTest {

    private final RunContext runContext = new RunContextFactory().of(Map.of());

    @Test
    void publishesRenderedMessageAndMapsResponse() throws Exception {
        Publish task = Publish.builder()
            .id("notify").type("io.tranto.plugin.aws.sns.Publish")
            .topicArn("arn:aws:sns:us-east-1:123:alerts")
            .subject("hi")
            .message("hello world")
            .build();

        SnsClient client = mock(SnsClient.class);
        when(client.publish(any(PublishRequest.class)))
            .thenReturn(PublishResponse.builder().messageId("msg-1").sequenceNumber("7").build());

        Publish spy = spy(task);
        doReturn(client).when(spy).client(any());

        Publish.PublishOutput out = spy.run(runContext);

        assertThat(out.messageId()).isEqualTo("msg-1");
        assertThat(out.sequenceNumber()).isEqualTo("7");

        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        org.mockito.Mockito.verify(client).publish(captor.capture());
        assertThat(captor.getValue().topicArn()).isEqualTo("arn:aws:sns:us-east-1:123:alerts");
        assertThat(captor.getValue().message()).isEqualTo("hello world");
        assertThat(captor.getValue().subject()).isEqualTo("hi");
    }
}
