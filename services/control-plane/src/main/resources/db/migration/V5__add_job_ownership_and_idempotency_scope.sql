ALTER TABLE jobs
    ADD COLUMN owner_issuer VARCHAR(512),
    ADD COLUMN owner_subject VARCHAR(255),
    ADD CONSTRAINT jobs_owner_claims_consistent CHECK (
        (owner_issuer IS NULL AND owner_subject IS NULL)
        OR (owner_issuer IS NOT NULL AND owner_subject IS NOT NULL)
    );

CREATE INDEX jobs_owner_created_at_idx
    ON jobs (owner_issuer, owner_subject, created_at DESC);

ALTER TABLE idempotency_records
    ADD COLUMN principal_issuer VARCHAR(512),
    ADD COLUMN principal_subject VARCHAR(255);

UPDATE idempotency_records
SET principal_issuer = 'legacy',
    principal_subject = 'legacy';

ALTER TABLE idempotency_records
    ALTER COLUMN principal_issuer SET NOT NULL,
    ALTER COLUMN principal_subject SET NOT NULL;

ALTER TABLE idempotency_records
    DROP CONSTRAINT idempotency_records_pk,
    ADD CONSTRAINT idempotency_records_pk PRIMARY KEY (
        operation_name,
        principal_issuer,
        principal_subject,
        idempotency_key
    );
