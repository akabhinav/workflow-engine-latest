package io.tranto.core.repositories.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.models.executions.Execution;
import io.tranto.core.repositories.ExecutionRepository;
import io.tranto.core.serializers.JacksonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link ExecutionRepository}: each execution is a JSON blob keyed by id, with the current
 * state promoted to a column so the scheduler/UI can query "running" or "failed" executions without
 * deserialising every row. Saves are upserts (immutable-execution semantics: each save replaces the
 * whole document).
 */
public class JdbcExecutionRepository implements ExecutionRepository {

    private final JdbcDatabase database;
    private final ObjectMapper mapper;

    public JdbcExecutionRepository(final JdbcDatabase database, final JacksonMapper mappers) {
        this.database = database;
        this.mapper = mappers.json();
    }

    @Override
    public Execution save(final Execution execution) {
        String sql = "MERGE INTO executions (id, namespace, flow_id, state, data) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, execution.getId());
            statement.setString(2, execution.getNamespace());
            statement.setString(3, execution.getFlowId());
            statement.setString(4, execution.getState() == null ? null : execution.getState().current().name());
            statement.setString(5, mapper.writeValueAsString(execution));
            statement.executeUpdate();
            return execution;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to save execution " + execution.getId(), e);
        }
    }

    @Override
    public Optional<Execution> findById(final String id) {
        String sql = "SELECT data FROM executions WHERE id = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapper.readValue(rs.getString("data"), Execution.class));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to load execution " + id, e);
        }
    }

    @Override
    public List<Execution> findAll() {
        List<Execution> executions = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT data FROM executions");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                executions.add(mapper.readValue(rs.getString("data"), Execution.class));
            }
            return executions;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to list executions", e);
        }
    }
}
