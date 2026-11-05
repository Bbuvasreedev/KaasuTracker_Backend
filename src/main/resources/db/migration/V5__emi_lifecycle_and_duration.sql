ALTER TABLE emis
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

ALTER TABLE emis
    ADD COLUMN IF NOT EXISTS duration_known BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE emis
    ADD COLUMN IF NOT EXISTS end_date DATE;

ALTER TABLE emis
    ADD COLUMN IF NOT EXISTS total_installments INTEGER;

ALTER TABLE emis
    ADD COLUMN IF NOT EXISTS creation_key VARCHAR(100);

UPDATE emis
SET status = CASE WHEN is_active THEN 'ACTIVE' ELSE 'INACTIVE' END
WHERE status IS NULL;

UPDATE emis
SET duration_known = FALSE,
    end_date = NULL,
    total_installments = NULL;

ALTER TABLE emis
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE emis
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE emis
    ADD CONSTRAINT ck_emis_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'COMPLETED'));

ALTER TABLE emis
    ADD CONSTRAINT ck_emis_known_duration CHECK (
        (duration_known = FALSE AND end_date IS NULL AND total_installments IS NULL)
        OR (duration_known = TRUE
            AND duration_months > 0
            AND total_installments = duration_months
            AND end_date IS NOT NULL)
    );

ALTER TABLE emis
    ADD CONSTRAINT uk_emis_user_creation_key UNIQUE (user_id, creation_key);

CREATE INDEX IF NOT EXISTS ix_emis_user_status
    ON emis(user_id, status);

CREATE INDEX IF NOT EXISTS ix_emi_installments_emi_status_date
    ON emi_installments(emi_id, status, due_date);
