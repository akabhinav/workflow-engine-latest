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
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

/**
 * Writes an item to a DynamoDB table. The {@code item} is a map of attribute names to values;
 * strings, numbers, booleans, null and nested maps/lists are converted to DynamoDB attribute types.
 *
 * <pre>
 * - id: save
 *   type: io.tranto.plugin.aws.dynamodb.PutItem
 *   region: us-east-1
 *   tableName: orders
 *   item:
 *     orderId: "{{ inputs.id }}"
 *     amount: 42
 *     paid: true
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Write an item to a DynamoDB table")
public class PutItem extends AbstractAwsTask implements RunnableTask<PutItem.PutItemOutput> {

    /** The DynamoDB table name (dynamic). */
    @PluginProperty(dynamic = true)
    private String tableName;

    /** The item to write, as attribute name → value. */
    @PluginProperty(dynamic = true)
    private Map<String, Object> item;

    @Override
    public PutItemOutput run(final RunContext runContext) throws Exception {
        String table = runContext.render(tableName);
        Map<String, Object> renderedItem = runContext.render(item);
        try (DynamoDbClient client = client(runContext)) {
            client.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(Attributes.toItem(renderedItem))
                .build());
            int written = renderedItem == null ? 0 : renderedItem.size();
            runContext.logger().info("Put item with {} attributes into {}", written, table);
            return new PutItemOutput(table, written);
        }
    }

    /** The DynamoDB client — overridable so unit tests can inject a mock. */
    protected DynamoDbClient client(final RunContext runContext) throws Exception {
        return configure(DynamoDbClient.builder(), runContext).build();
    }

    /**
     * @param tableName  the table written to
     * @param attributes the number of attributes written
     */
    public record PutItemOutput(String tableName, int attributes) implements Output {
    }
}
