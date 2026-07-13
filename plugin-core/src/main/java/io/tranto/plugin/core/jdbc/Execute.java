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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one or more SQL statements ({@code DDL}/{@code INSERT}/{@code UPDATE}/{@code DELETE}/
 * {@code MERGE}) against a JDBC database. Multiple statements may be separated by {@code ;} — handy
 * for creating and seeding schema in a single task. Runs in a transaction and commits on success.
 *
 * <p>Output: {@code affectedRows} (total rows changed across all statements).</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(title = "Execute SQL statements (DDL/DML)")
public class Execute extends Task implements RunnableTask<Execute.ExecuteOutput> {

    @PluginProperty(dynamic = true)
    private String url;

    @PluginProperty
    private String username;

    @PluginProperty
    private String password;

    @PluginProperty(dynamic = true)
    private String sql;

    @Override
    public ExecuteOutput run(final RunContext runContext) throws Exception {
        String renderedUrl = runContext.render(url);
        String renderedSql = runContext.render(sql);

        String user = username != null ? username : "sa";
        String pass = password != null ? password : "";

        int affected = 0;
        try (Connection connection = DriverManager.getConnection(renderedUrl, user, pass)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String single : splitStatements(renderedSql)) {
                    affected += Math.max(statement.executeUpdate(single), 0);
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
        runContext.logger().info("Executed SQL, {} row(s) affected", affected);
        return new ExecuteOutput(affected);
    }

    /** Split on semicolons into individual, non-blank statements. */
    private static List<String> splitStatements(final String sql) {
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    /** @param affectedRows total rows changed across all statements. */
    public record ExecuteOutput(int affectedRows) implements Output {
    }
}
