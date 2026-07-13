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
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.List;
import java.util.Map;

/**
 * Queries a DynamoDB table by key condition and returns the matching items as plain maps.
 *
 * <pre>
 * - id: recent
 *   type: io.tranto.plugin.aws.dynamodb.Query
 *   region: us-east-1
 *   tableName: orders
 *   keyConditionExpression: "customerId = :c"
 *   expressionAttributeValues:
 *     ":c": "{{ inputs.customer }}"
 *   limit: 25
 * </pre>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Query a DynamoDB table")
public class Query extends AbstractAwsTask implements RunnableTask<Query.QueryOutput> {

    /** The DynamoDB table name (dynamic). */
    @PluginProperty(dynamic = true)
    private String tableName;

    /** The key condition expression, e.g. {@code "customerId = :c"} (dynamic). */
    @PluginProperty(dynamic = true)
    private String keyConditionExpression;

    /** Values bound into the expression (e.g. {@code ":c"} → a value), as name → value. */
    @PluginProperty(dynamic = true)
    private Map<String, Object> expressionAttributeValues;

    /** Optional maximum number of items to return. */
    @PluginProperty
    private Integer limit;

    @Override
    public QueryOutput run(final RunContext runContext) throws Exception {
        String table = runContext.render(tableName);
        Map<String, Object> renderedValues = runContext.render(expressionAttributeValues);
        QueryRequest.Builder request = QueryRequest.builder()
            .tableName(table)
            .keyConditionExpression(runContext.render(keyConditionExpression))
            .expressionAttributeValues(Attributes.toItem(renderedValues));
        if (limit != null) {
            request.limit(limit);
        }
        try (DynamoDbClient client = client(runContext)) {
            QueryResponse response = client.query(request.build());
            List<Map<String, Object>> items = response.items().stream()
                .map(Attributes::fromItem)
                .toList();
            return new QueryOutput(items, response.count());
        }
    }

    /** The DynamoDB client — overridable so unit tests can inject a mock. */
    protected DynamoDbClient client(final RunContext runContext) throws Exception {
        return configure(DynamoDbClient.builder(), runContext).build();
    }

    /**
     * @param items the matching items, each a plain attribute map
     * @param count the number of items returned
     */
    public record QueryOutput(List<Map<String, Object>> items, int count) implements Output {
    }
}
