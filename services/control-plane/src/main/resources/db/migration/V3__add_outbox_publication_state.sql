ALTER TABLE jobs
    DROP CONSTRAINT jobs_status_valid,
    ADD CONSTRAINT jobs_status_valid CHECK (
        status IN ('PENDING', 'SCHEDULED', 'DISPATCHING', 'DISPATCHED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    );

ALTER TABLE outbox_events
    ADD COLUMN terminal_failure_at TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD CONSTRAINT outbox_events_terminal_state_exclusive CHECK (
        num_nonnulls(published_at, terminal_failure_at, cancelled_at) <= 1
    );

DROP INDEX outbox_events_ready_idx;

CREATE INDEX outbox_events_ready_idx
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL
      AND terminal_failure_at IS NULL
      AND cancelled_at IS NULL;
