package io.flowforge.controlplane.idempotency.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @EmbeddedId
    private Id id;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requestFingerprint;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public static IdempotencyRecord create(
            String operationName,
            String key,
            String requestFingerprint,
            UUID jobId,
            Instant createdAt,
            Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = new Id(operationName, key);
        record.requestFingerprint = requestFingerprint;
        record.jobId = jobId;
        record.createdAt = createdAt;
        record.expiresAt = expiresAt;
        return record;
    }

    public Id getId() {
        return id;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getJobId() {
        return jobId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Embeddable
    public static class Id implements Serializable {

        @Column(name = "operation_name", nullable = false, length = 100)
        private String operationName;

        @Column(name = "idempotency_key", nullable = false, length = 255)
        private String key;

        protected Id() {
        }

        public Id(String operationName, String key) {
            this.operationName = operationName;
            this.key = key;
        }

        public String getOperationName() {
            return operationName;
        }

        public String getKey() {
            return key;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return Objects.equals(operationName, that.operationName)
                    && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operationName, key);
        }
    }
}
