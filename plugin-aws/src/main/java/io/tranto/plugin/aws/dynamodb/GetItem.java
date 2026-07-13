package io.tranto.plugin.aws.dynamodb;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.runners.RunContext;
import io.tranto.plugin.aws.AbstractAwsTask;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;

/**
 * Reads an item from a DynamoDB table by primary key. The returned item is exposed as a plain map
 * ({@code {{ outputs.<id>.item.<attr> }}}); {@code found} is false when no item matches the key.
 *
 * <pre>
 * - id: load
 *   type: io.tranto.plugin.aws.dynamodb.GetItem
 *   region: us-east-1
 *   tableName: orders
 *   key:
 *     orderId: "{{ inputs.id }}"
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Read an item from a DynamoDB table")
public class GetItem extends AbstractAwsTask implements RunnableTask<GetItem.GetItemOutput> {

    /** The DynamoDB table name (dynamic). */
    @PluginProperty(dynamic = true)
    private String tableName;

    /** The primary key of the item, as attribute name → value. */
    @PluginProperty(dynamic = true)
    private Map<String, Object> key;

    @Override
    public GetItemOutput run(final RunContext runContext) throws Exception {
        String table = runContext.render(tableName);
        Map<String, Object> renderedKey = runContext.render(key);
        try (DynamoDbClient client = client(runContext)) {
            GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Attributes.toItem(renderedKey))
                .build());
            boolean found = response.hasItem();
            Map<String, Object> item = found ? Attributes.fromItem(response.item()) : Map.of();
            return new GetItemOutput(item, found);
        }
    }

    /** The DynamoDB client — overridable so unit tests can inject a mock. */
    protected DynamoDbClient client(final RunContext runContext) throws Exception {
        return configure(DynamoDbClient.builder(), runContext).build();
    }

    /**
     * @param item  the item's attributes as a plain map (empty when not found)
     * @param found whether an item matched the key
     */
    public record GetItemOutput(Map<String, Object> item, boolean found) implements Output {
    }
}
