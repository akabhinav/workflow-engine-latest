package io.tranto.plugin.aws.dynamodb;

import io.tranto.core.runners.RunContext;
import io.tranto.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit-tests the DynamoDB tasks against a mocked client, plus the plain-value ⇄ AttributeValue converter. */
class DynamoDbTest {

    private final RunContext runContext = new RunContextFactory().of(Map.of());

    @Test
    void attributeConverterRoundTripsScalarsAndNesting() {
        Map<String, Object> in = Map.of(
            "s", "text",
            "n", 42L,
            "b", true,
            "list", List.of("a", "b"),
            "map", Map.of("inner", 3L));

        Map<String, AttributeValue> item = Attributes.toItem(in);
        assertThat(item.get("s").s()).isEqualTo("text");
        assertThat(item.get("n").n()).isEqualTo("42");
        assertThat(item.get("b").bool()).isTrue();

        Map<String, Object> back = Attributes.fromItem(item);
        assertThat(back.get("s")).isEqualTo("text");
        assertThat(back.get("n")).isEqualTo(42L);
        assertThat(back.get("b")).isEqualTo(true);
        assertThat(back.get("list")).isEqualTo(List.of("a", "b"));
        assertThat(back.get("map")).isEqualTo(Map.of("inner", 3L));
    }

    @Test
    void putItemWritesConvertedItem() throws Exception {
        PutItem task = PutItem.builder()
            .id("save").type("io.tranto.plugin.aws.dynamodb.PutItem")
            .tableName("orders")
            .item(Map.of("orderId", "o-1", "amount", 42L))
            .build();

        DynamoDbClient client = mock(DynamoDbClient.class);
        PutItem spy = spy(task);
        doReturn(client).when(spy).client(any());

        PutItem.PutItemOutput out = spy.run(runContext);
        assertThat(out.tableName()).isEqualTo("orders");
        assertThat(out.attributes()).isEqualTo(2);

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().item().get("orderId").s()).isEqualTo("o-1");
        assertThat(captor.getValue().item().get("amount").n()).isEqualTo("42");
    }

    @Test
    void getItemMapsFoundItem() throws Exception {
        GetItem task = GetItem.builder()
            .id("load").type("io.tranto.plugin.aws.dynamodb.GetItem")
            .tableName("orders")
            .key(Map.of("orderId", "o-1"))
            .build();

        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(Map.of("orderId", AttributeValue.builder().s("o-1").build(),
                    "amount", AttributeValue.builder().n("42").build()))
                .build());

        GetItem spy = spy(task);
        doReturn(client).when(spy).client(any());

        GetItem.GetItemOutput out = spy.run(runContext);
        assertThat(out.found()).isTrue();
        assertThat(out.item().get("orderId")).isEqualTo("o-1");
        assertThat(out.item().get("amount")).isEqualTo(42L);
    }

    @Test
    void getItemReportsNotFound() throws Exception {
        GetItem task = GetItem.builder()
            .id("load").type("io.tranto.plugin.aws.dynamodb.GetItem")
            .tableName("orders")
            .key(Map.of("orderId", "missing"))
            .build();

        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        GetItem spy = spy(task);
        doReturn(client).when(spy).client(any());

        GetItem.GetItemOutput out = spy.run(runContext);
        assertThat(out.found()).isFalse();
        assertThat(out.item()).isEmpty();
    }

    @Test
    void queryMapsItemsAndCount() throws Exception {
        Query task = Query.builder()
            .id("recent").type("io.tranto.plugin.aws.dynamodb.Query")
            .tableName("orders")
            .keyConditionExpression("customerId = :c")
            .expressionAttributeValues(Map.of(":c", "cust-1"))
            .build();

        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder()
                .items(List.of(Map.of("orderId", AttributeValue.builder().s("o-1").build())))
                .count(1)
                .build());

        Query spy = spy(task);
        doReturn(client).when(spy).client(any());

        Query.QueryOutput out = spy.run(runContext);
        assertThat(out.count()).isEqualTo(1);
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).get("orderId")).isEqualTo("o-1");

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().keyConditionExpression()).isEqualTo("customerId = :c");
        assertThat(captor.getValue().expressionAttributeValues().get(":c").s()).isEqualTo("cust-1");
    }
}
