CREATE INDEX IF NOT EXISTS ix_expenses_user_date_id
    ON expenses(user_id, expense_date, id);

CREATE INDEX IF NOT EXISTS ix_emis_user_active
    ON emis(user_id, is_active);

CREATE INDEX IF NOT EXISTS ix_borrowed_funds_user_status_date
    ON borrowed_funds(user_id, status, fund_date);
