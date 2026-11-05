DELETE FROM expense_funding_allocations
WHERE funding_source = 'ADDITIONAL';

DROP INDEX IF EXISTS ix_expense_allocations_source;

ALTER TABLE expense_funding_allocations
    DROP CONSTRAINT IF EXISTS ck_allocation_source_reference;

ALTER TABLE expense_funding_allocations
    DROP CONSTRAINT IF EXISTS uk_expense_additional_source;

ALTER TABLE expense_funding_allocations
    DROP CONSTRAINT IF EXISTS expense_funding_allocations_funding_source_check;

ALTER TABLE expense_funding_allocations
    DROP COLUMN IF EXISTS additional_fund_id;

ALTER TABLE expense_funding_allocations
    ADD CONSTRAINT ck_allocation_source_reference CHECK (
        (funding_source = 'SALARY'
            AND salary_id IS NOT NULL
            AND opening_balance_id IS NULL
            AND borrowed_fund_id IS NULL)
        OR (funding_source = 'CARRY_FORWARD'
            AND salary_id IS NULL
            AND opening_balance_id IS NOT NULL
            AND borrowed_fund_id IS NULL)
        OR (funding_source = 'BORROWED'
            AND salary_id IS NULL
            AND opening_balance_id IS NULL
            AND borrowed_fund_id IS NOT NULL)
    );

ALTER TABLE expense_funding_allocations
    ADD CONSTRAINT ck_expense_funding_allocations_funding_source
        CHECK (funding_source IN ('SALARY', 'CARRY_FORWARD', 'BORROWED'));

DROP TABLE IF EXISTS additional_funds;

CREATE INDEX IF NOT EXISTS ix_expense_allocations_source
    ON expense_funding_allocations(funding_source, salary_id, opening_balance_id, borrowed_fund_id);
