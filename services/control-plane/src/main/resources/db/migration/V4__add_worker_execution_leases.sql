ALTER TABLE jobs
    ADD COLUMN lease_owner VARCHAR(200),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN cancel_requested_at TIMESTAMPTZ,
    ADD COLUMN result JSONB,
    ADD COLUMN error JSONB,
    DROP CONSTRAINT jobs_status_valid,
    ADD CONSTRAINT jobs_status_valid CHECK (
        status IN (
            'PENDING', 'SCHEDULED', 'DISPATCHING', 'DISPATCHED', 'RUNNING',
            'COMPLETED', 'FAILED', 'CANCELLED'
        )
    ),
    ADD CONSTRAINT jobs_execution_lease_consistent CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT jobs_fencing_token_nonnegative CHECK (fencing_token >= 0);

CREATE INDEX jobs_execution_ready_idx
    ON jobs (next_attempt_at, created_at)
    WHERE status = 'DISPATCHED';
CREATE INDEX jobs_execution_lease_idx
    ON jobs (lease_expires_at)
    WHERE status = 'RUNNING';

CREATE TABLE job_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(200) NOT NULL,
    fencing_token BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    failure_type VARCHAR(20),
    error JSONB,
    result JSONB,
    CONSTRAINT job_attempts_number_positive CHECK (attempt_number >= 1),
    CONSTRAINT job_attempts_fencing_token_positive CHECK (fencing_token >= 1),
    CONSTRAINT job_attempts_status_valid CHECK (
        status IN ('RUNNING', 'COMPLETED', 'RETRY_WAIT', 'FAILED', 'ABANDONED', 'CANCELLED')
    ),
    CONSTRAINT job_attempts_failure_type_valid CHECK (
        failure_type IS NULL OR failure_type IN ('TRANSIENT', 'PERMANENT', 'WORKER_LOST')
    ),
    CONSTRAINT job_attempts_job_number_unique UNIQUE (job_id, attempt_number)
);

CREATE INDEX job_attempts_job_started_idx ON job_attempts (job_id, started_at DESC);
CREATE INDEX job_attempts_lease_idx
    ON job_attempts (lease_expires_at)
    WHERE status = 'RUNNING';
