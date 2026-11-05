ALTER TABLE expenses
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uk_expenses_user_idempotency_key
    ON expenses(user_id, idempotency_key);
