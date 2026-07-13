package io.tranto.core.repositories.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tranto.core.jdbc.JdbcDatabase;
import io.tranto.core.models.flows.Flow;
import io.tranto.core.repositories.FlowRepository;
import io.tranto.core.serializers.JacksonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC {@link FlowRepository}: stores each flow as a JSON blob keyed by {@code tenant|namespace|id}
 * (revision-latest), promoting the natural key into columns for indexed lookup. Uses a portable
 * {@code MERGE} upsert so the same code runs on H2, Postgres and MySQL.
 */
public class JdbcFlowRepository implements FlowRepository {

    private final JdbcDatabase database;
    private final ObjectMapper mapper;

    public JdbcFlowRepository(final JdbcDatabase database, final JacksonMapper mappers) {
        this.database = database;
        this.mapper = mappers.json();
    }

    @Override
    public Flow save(final Flow flow) {
        String tenant = flow.getTenantId() == null ? "" : flow.getTenantId();
        int revision = flow.getRevision() == null ? 1 : flow.getRevision();
        String sql = "MERGE INTO flows (tenant, namespace, id, revision, data) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenant);
            statement.setString(2, flow.getNamespace());
            statement.setString(3, flow.getId());
            statement.setInt(4, revision);
            statement.setString(5, mapper.writeValueAsString(flow));
            statement.executeUpdate();
            return flow;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to save flow " + flow.getId(), e);
        }
    }

    @Override
    public Optional<Flow> findById(final String tenantId, final String namespace,
                                   final String id, final Integer revision) {
        String sql = "SELECT data FROM flows WHERE tenant = ? AND namespace = ? AND id = ?";
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId == null ? "" : tenantId);
            statement.setString(2, namespace);
            statement.setString(3, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapper.readValue(rs.getString("data"), Flow.class));
            }
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to load flow " + id, e);
        }
    }

    @Override
    public List<Flow> findAll() {
        List<Flow> flows = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT data FROM flows");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                flows.add(mapper.readValue(rs.getString("data"), Flow.class));
            }
            return flows;
        } catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to list flows", e);
        }
    }
}
