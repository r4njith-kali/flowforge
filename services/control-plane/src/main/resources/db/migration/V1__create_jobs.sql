CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    job_type VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    retry_delay_seconds INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT jobs_job_type_not_blank CHECK (length(trim(job_type)) > 0),
    CONSTRAINT jobs_status_valid CHECK (
        status IN ('PENDING', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT jobs_attempt_count_nonnegative CHECK (attempt_count >= 0),
    CONSTRAINT jobs_max_attempts_positive CHECK (max_attempts >= 1),
    CONSTRAINT jobs_retry_delay_nonnegative CHECK (retry_delay_seconds >= 0)
);

CREATE INDEX jobs_status_scheduled_at_idx ON jobs (status, scheduled_at);
CREATE INDEX jobs_job_type_created_at_idx ON jobs (job_type, created_at DESC);
CREATE INDEX jobs_created_at_idx ON jobs (created_at DESC);
