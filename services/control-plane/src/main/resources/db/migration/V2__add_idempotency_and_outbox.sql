CREATE TABLE idempotency_records (
    operation_name VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    job_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_records_pk PRIMARY KEY (operation_name, idempotency_key),
    CONSTRAINT idempotency_records_job_fk
        FOREIGN KEY (job_id) REFERENCES jobs (id) ON DELETE CASCADE,
    CONSTRAINT idempotency_records_operation_not_blank
        CHECK (length(trim(operation_name)) > 0),
    CONSTRAINT idempotency_records_key_not_blank
        CHECK (length(trim(idempotency_key)) > 0),
    CONSTRAINT idempotency_records_fingerprint_format
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_records_expiry_after_creation
        CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_records_expiry_idx ON idempotency_records (expires_at);
CREATE INDEX idempotency_records_job_idx ON idempotency_records (job_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    aggregate_id UUID NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    claimed_by VARCHAR(200),
    claim_expires_at TIMESTAMPTZ,
    CONSTRAINT outbox_events_event_type_not_blank CHECK (length(trim(event_type)) > 0),
    CONSTRAINT outbox_events_schema_version_positive CHECK (schema_version >= 1),
    CONSTRAINT outbox_events_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT outbox_events_claim_consistent CHECK (
        (claimed_by IS NULL AND claim_expires_at IS NULL)
        OR (claimed_by IS NOT NULL AND claim_expires_at IS NOT NULL)
    )
);

CREATE INDEX outbox_events_ready_idx
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;
CREATE INDEX outbox_events_claim_expiry_idx
    ON outbox_events (claim_expires_at)
    WHERE published_at IS NULL AND claimed_by IS NOT NULL;
CREATE INDEX outbox_events_aggregate_idx
    ON outbox_events (aggregate_id, created_at);
