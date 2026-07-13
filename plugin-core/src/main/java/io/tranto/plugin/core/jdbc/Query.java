package io.tranto.plugin.core.jdbc;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a SQL {@code SELECT} against any JDBC database and exposes the result set as structured
 * output. Uses only the JDK's {@code java.sql} — the driver (H2/Postgres/MySQL) just needs to be on
 * the runtime classpath.
 *
 * <p>Outputs: {@code rows} (list of column→value maps), {@code size}, {@code firstColumn} (the first
 * column across all rows — handy to feed a {@code Loop}), and {@code firstValue} (the top-left cell,
 * ideal for {@code COUNT(*)}/{@code SUM(...)} scalars).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Run a SQL query and capture the rows")
public class Query extends Task implements RunnableTask<Query.QueryOutput> {

    @PluginProperty(dynamic = true)
    private String url;

    @PluginProperty
    private String username;

    @PluginProperty
    private String password;

    @PluginProperty(dynamic = true)
    private String sql;

    /** Optional positional parameters bound to {@code ?} placeholders (rendered before binding). */
    @PluginProperty(dynamic = true)
    private List<String> parameters;

    @Override
    public QueryOutput run(final RunContext runContext) throws Exception {
        String renderedUrl = runContext.render(url);
        String renderedSql = runContext.render(sql);

        String user = username != null ? username : "sa";
        String pass = password != null ? password : "";

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(renderedUrl, user, pass);
             PreparedStatement statement = connection.prepareStatement(renderedSql)) {
            bindParameters(statement, runContext);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }

        List<Object> firstColumn = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            firstColumn.add(row.isEmpty() ? null : row.values().iterator().next());
        }
        Object firstValue = rows.isEmpty() || rows.get(0).isEmpty()
            ? null : rows.get(0).values().iterator().next();

        runContext.logger().info("Query returned {} row(s)", rows.size());
        return new QueryOutput(rows, rows.size(), firstColumn, firstValue);
    }

    private void bindParameters(final PreparedStatement statement, final RunContext runContext) throws Exception {
        if (parameters == null) {
            return;
        }
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, runContext.render(parameters.get(i)));
        }
    }

    /**
     * @param rows        the result rows (column label → value)
     * @param size        the number of rows
     * @param firstColumn the first column across all rows
     * @param firstValue  the top-left cell (for scalar queries), or null when empty
     */
    public record QueryOutput(List<Map<String, Object>> rows, int size,
                              List<Object> firstColumn, Object firstValue) implements Output {
    }
}
