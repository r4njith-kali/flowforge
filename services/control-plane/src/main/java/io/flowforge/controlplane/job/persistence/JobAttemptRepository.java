package io.flowforge.controlplane.job.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowforge.controlplane.job.domain.JobAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JobAttemptRepository {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JobAttemptRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<JobAttempt> findByJobIdOrderByAttemptNumber(UUID jobId) {
        return jdbcTemplate.query("""
                SELECT id, attempt_number, worker_id, status, started_at, heartbeat_at,
                       lease_expires_at, finished_at, failure_type,
                       error::text AS error_json, result::text AS result_json
                FROM job_attempts
                WHERE job_id = ?
                ORDER BY attempt_number ASC
                """, this::mapAttempt, jobId);
    }

    private JobAttempt mapAttempt(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobAttempt(
                resultSet.getObject("id", UUID.class),
                resultSet.getInt("attempt_number"),
                resultSet.getString("worker_id"),
                resultSet.getString("status"),
                toInstant(resultSet.getTimestamp("started_at")),
                toInstant(resultSet.getTimestamp("heartbeat_at")),
                toInstant(resultSet.getTimestamp("lease_expires_at")),
                toInstant(resultSet.getTimestamp("finished_at")),
                resultSet.getString("failure_type"),
                readJsonObject(resultSet.getString("error_json")),
                readJsonObject(resultSet.getString("result_json")));
    }

    private Map<String, Object> readJsonObject(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT_TYPE);
        } catch (IOException exception) {
            throw new SQLException("Stored job attempt JSON is invalid", exception);
        }
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
