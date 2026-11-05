# KaasuTracker backend

Spring Boot REST API for KaasuTracker. Finance endpoints use the authenticated
Supabase JWT subject as the owner; clients must not send a user ID.

## Monthly finance rules

- `expenses.is_confirmed = true` and `expense_date` within the requested calendar
  month determine eligible expenses. The reported expense amount is
  `amount - refunded_amount`.
- A month-specific salary overrides the existing `app_users.base_salary`. When
  no monthly salary is recorded, that existing profile value remains the
  backward-compatible salary fallback.
- Expenses are allocated in this order: monthly salary, established opening
  carry-forward, then borrowed funds dated in the month. Any remainder is
  unfunded overspending. Additional funds are not a supported funding source.
- Automatic allocations are recalculated for the whole month in expense-date
  and ID order whenever an expense, refund, salary, carry-forward, or borrowed
  fund changes. An explicit funding `PUT` saves a manually selected allocation;
  later recalculations preserve that selection and recalculate all other
  expenses and subsequent established carry-forward balances.
- A refund reduces the expense's net eligible amount and all affected monthly
  allocations are recalculated. A manually selected split returns to automatic
  allocation when a refund is recorded or the expense amount, month, or
  confirmation state changes. Refunds never create additional or borrowed fund
  records.
- Carry-forward establishment copies the previous month's unspent balances into
  a unique opening balance for the requested month. It is an explicit,
  idempotent operation, so retrying it cannot create another opening balance.
- `unfundedOverspending = max(0, eligible actual expenses - valid allocations)`.
  Salary, carry-forward, and borrowed balances are reported separately.
  Existing additional-fund summary fields remain in the response as zero for
  client compatibility. `totalAvailableFunding` is the sum of remaining source
  balances. Remaining salary is not reduced by scheduled EMIs.
- EMI schedules are monthly and run for `durationMonths` installments, starting
  with the first due date on or after the configured start date. If the selected
  due day does not exist in a month, its last calendar day is used. Installments due today or
  overdue are processed by the scheduled workflow at 08:00 server-local time.
  The monthly-summary API is read-only and reports unpaid due installments
  without waiting for EMI processing. The overdue policy catches up every
  unpaid installment since the schedule start, in due-date order, and never
  creates a future-dated installment expense.
- Each installment has a unique EMI/date record and a linked confirmed expense.
  Its expense allocation is part of the same transaction as the payment status.
  User-row locks serialize this work across scheduler instances and retries;
  transaction failures roll back the installment, expense, allocations, and
  paid status together. Generated EMI expenses cannot be edited or deleted
  through the expense service.
- The monthly summary distinguishes scheduled commitments from paid expense
  amounts with `monthlyEmiScheduledAmount`, `monthlyEmiPaidAmount`,
  `emiDueTodayAmount` (all due today), and `emiDueTodayUnpaidAmount`.
  `nextEmiDueDate` and `nextEmiPaymentStatus` track the next unpaid installment
  (`DUE`, `OVERDUE`, or `SCHEDULED`). EMI expenses are
  included once in `totalEligibleActualExpenses`.

## Finance API

All routes below are authenticated and use JSON unless noted.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/finance/monthly-summary?year=2026&month=10` | Typed monthly totals, balances, EMI schedule |
| `GET`, `POST` | `/api/v1/emis` | List owned EMIs or create an EMI schedule |
| `PUT` | `/api/v1/emis/{emiId}/status` | Activate or deactivate an owned EMI |
| `PUT` | `/api/v1/emis/{emiId}/duration` | Set duration/start date for an EMI with unknown legacy duration |
| `GET` | `/api/v1/expenses/{expenseId}` | Read an owned expense's persisted details |
| `DELETE` | `/api/v1/expenses/{expenseId}` | Delete an owned expense and recalculate monthly funding |
| `POST` | `/api/v1/receipts/extract` | Extract receipt fields from an authenticated multipart upload for review |
| `PUT` | `/api/v1/finance/monthly-salary` | Save/replace `{ "year": 2026, "month": 10, "amount": 50000.00 }` |
| `GET` | `/api/v1/finance/carry-forward?year=2026&month=10` | Read established opening amount and calculated prior closing balance |
| `POST` | `/api/v1/finance/carry-forward?year=2026&month=10` | Establish the opening balance once |
| `GET`, `POST` | `/api/v1/borrowed-funds` | List receipts (optional paired `year` and `month`) or record one |
| `PUT`, `DELETE` | `/api/v1/borrowed-funds/{id}` | Update or deactivate a borrowed receipt |
| `GET`, `PUT` | `/api/v1/expenses/{expenseId}/funding` | Read or replace an owned expense's funding allocations |
| `POST` | `/api/v1/expenses/{id}/refunds` | Record a refund amount against an expense |

Fund request bodies use `amount`, `fundDate`, and `sourceDescription`; borrowed
fund requests can also include `purpose` and `outstandingRepaymentBalance`.
Funding is updated with:

```json
{
  "allocations": [
    {"fundingSource": "SALARY", "amount": 5000.00},
    {"fundingSource": "CARRY_FORWARD", "sourceId": 12, "amount": 1500.00},
    {"fundingSource": "BORROWED", "sourceId": 34, "amount": 2000.00}
  ]
}
```

The allocation sum must equal the expense amount. Salary `sourceId` is optional
and, when supplied, must identify the authenticated user's salary for that
expense month. Carry-forward IDs come from the carry-forward response, and
borrowed-fund IDs come from the fund response; both must belong to the user and
be eligible for that month. `PUT /api/v1/expenses/{expenseId}/funding` responds
with an `ExpenseFundingResource` object containing the updated `expense` and
`allocations` array; `GET` continues to return the allocation array. For
example:

```json
{
  "expense": {
    "id": 123,
    "amount": 8500.00,
    "refundedAmount": 0.00,
    "category": "Travel",
    "merchant": null,
    "description": null,
    "expenseDate": "2026-10-15",
    "receiptUrl": null,
    "isConfirmed": true
  },
  "allocations": [
    {"id": 1, "fundingSource": "SALARY", "sourceId": 10, "amount": 5000.00},
    {"id": 2, "fundingSource": "CARRY_FORWARD", "sourceId": 12, "amount": 1500.00},
    {"id": 3, "fundingSource": "BORROWED", "sourceId": 34, "amount": 2000.00}
  ]
}
```

Expense, allocation, salary, carry-forward, borrowed-fund, and EMI-payment writes lock the
authenticated user's row inside a database transaction, serializing concurrent
spending attempts for that user.
Unfunded amounts are reported for automatically allocated expenses. Invalid
allocation input returns `400`; unknown or cross-user expenses return `404`;
manual requests exceeding a source's available balance, refunded expenses, and
EMI-linked expenses return `409 Conflict`.
Expense detail returns the existing schema-backed fields (`id`, amount,
refunded amount, category, merchant, description, expense date, receipt URL,
and confirmation status). Payment method and notes are not present in the
current expense entity/schema. Expense allocations remain available through
the existing funding detail route. Removing a regular expense deletes its
funding allocations and recalculates that month's allocations and later
established carry-forward balances in the same PostgreSQL transaction. Linked
scheduled EMI payment expenses are protected from deletion.

EMI creation requires `title`, `amount`, `dueDateDay`, `startDate`, and
`durationMonths` (1-1200). The response includes `id`, `status`, `isActive`,
`startDate`, `durationMonths`, calculated `endDate`, `totalInstallments`,
`paidInstallments`, and `remainingInstallments`, along with the original title,
amount, and due-day fields. The final installment uses the regular EMI amount.
Creation generates all scheduled installment rows in the same transaction;
the database's unique `(emi_id, due_date)` constraint and optional
`Idempotency-Key` request header prevent duplicate schedule rows and make
retries idempotent when the same key and payload are reused. Reusing a key with
a different payload returns `409 Conflict`.

Status updates accept `{"status":"ACTIVE"}` or `{"status":"INACTIVE"}`.
Deactivation retains all installment and payment history but excludes pending
installments from automatic processing and upcoming summary obligations.
Reactivation retains the existing schedule and does not create duplicates.
`COMPLETED` is assigned by the backend only after all known-duration
installments are paid; it cannot be reactivated.

V5 marks every pre-existing EMI duration as unknown instead of assuming that
the V3 database default of 12 months was the user's actual loan term. The
stored legacy duration value is preserved but is not exposed as authoritative:
duration, end date, total count, and remaining count are returned as `null`
until the owner supplies terms through `PUT /api/v1/emis/{emiId}/duration`.
The V3 `started_on` backfill date is retained as the legacy start date because
the original schedule date cannot be reconstructed safely. The owner may
correct that date through the duration endpoint only before any installment
history exists. Unknown-duration active schedules continue monthly until a
duration is supplied; inactive unknown-duration schedules are not processed.

## Receipt extraction

`POST /api/v1/receipts/extract` accepts an authenticated `multipart/form-data`
request with a single `file` field. JPEG, PNG, WebP, and PDF files up to 10 MiB
are accepted. The backend checks the extension, declared MIME type when
provided, and file signature; Spring's multipart request limit is 11 MiB.
Extraction runs in a bounded worker pool and times out after 30 seconds.

The typed response contains nullable `merchant`, `receiptDate`, `receiptTime`,
`totalAmount`, `subtotal`, `tax`, `discount`, `currency`, `suggestedCategory`,
`paymentMethod`, `invoiceNumber`, `items`, `confidenceScore`, and
`uncertainFields`. Monetary values are decimal numbers; fields the model cannot
identify remain `null` and are not replaced with guessed values. `items`
contains `description`, `quantity`, `unitPrice`, and `lineTotal` when available.
`uncertainFields` identifies uncertain field names.

Set the backend-only `GCP_API_KEY` environment variable to enable Google GenAI
receipt extraction. `GCP_GENAI_MODEL` optionally selects the model and defaults
to `gemini-2.0-flash`. With no API key configured, the application can start,
but extraction returns `503 Service Unavailable`. Provider credentials must
never be placed in Flutter configuration. Provider calls are mocked in tests;
live provider operation is not implied by those tests.

Extraction does not create an expense or retain the uploaded bytes. The client
must let the user review and edit the result, then submit their confirmed values
to the existing authenticated `POST /api/v1/expenses` endpoint. That route
accepts the existing expense JSON fields (`amount`, `category`, optional
`merchant` and `description`, `expenseDate`, optional `receiptUrl`, and
`isConfirmed`). Send a stable `Idempotency-Key` header when confirming to make
retries safe: a replay with the same key and payload returns the existing
expense, while reusing the key with a different payload returns `409 Conflict`.
The existing expense service remains responsible for authenticated ownership,
persistence, and funding recalculation.

## Persistence and migration

`src/main/resources/db/migration/V1__monthly_fund_allocation.sql`,
`V2__remove_additional_fund_source.sql`, `V3__track_emi_installments.sql`,
`V4__index_finance_lookup_paths.sql`, and
`V5__emi_lifecycle_and_duration.sql`, `V6__expense_creation_idempotency.sql`,
and `V7__manual_expense_funding_allocations.sql` are the Flyway migrations. V2 removes
the additional-fund API data, allocations, and table while preserving the
existing summary response fields as zero. V3 adds EMI schedule start dates,
unique installment/payment records, and the expense-to-installment link.
Existing EMI schedules start at migration date; new schedules start on their
creation date. V4 adds indexes for the user/date/status lookups used in
financial recalculation and summary queries. Flyway is enabled at application
startup and configured to baseline an existing non-empty schema at version `0`,
so version `1` applies without dropping existing records. V1 adds
`refunded_amount` to expenses and creates monthly salary, borrowed fund,
opening carry-forward, and allocation tables. Hibernate's existing
`ddl-auto: update` setting remains enabled for compatibility.
V7 adds a `funding_allocation_manual` marker to expenses so subsequent
recalculations preserve explicitly selected funding splits without changing
legacy automatic allocations.

The Supabase datasource uses the primary Spring datasource for both Hibernate
and Flyway; no separate Flyway URL or credentials are configured. Supply
`SUPABASE_DB_HOST`, `SUPABASE_DB_PORT`, `SUPABASE_DB_NAME`,
`SUPABASE_DB_USERNAME`, and `SUPABASE_DB_PASSWORD` from the Supabase Dashboard
**Connect** dialog. Do not infer the pooler host from the region or type the
project reference manually. For the shared session pooler, the Dashboard
connection details use port `5432` and a username in the
`postgres.<project-ref>` form. For the shared transaction pooler, they use port
`6543` and the same tenant-qualified username; the configured JDBC URL disables
prepared statements for this mode. If selecting direct or dedicated pooler
connections, use the exact host, port, and username displayed for that method.
The pooler tenant error means the endpoint did not receive a valid tenant
identifier, commonly because the selected host or shared-pooler username does
not match the Dashboard connection details. Verify all five variables as a
matched set, including the database name, and ensure the database password is
current. Missing variables prevent datasource configuration rather than
falling back to an embedded database or fabricated credentials.

Hikari leak detection is enabled to help diagnose connections checked out for
more than 20 seconds. The monthly-summary endpoint logs slow-request duration
and pool active/idle/waiting counts without logging user or financial details.

Before deploying, back up the Supabase database and ensure the backend connects
to the intended schema. The migration expects the existing `app_users` and
`expenses` tables/columns created by this application; it creates those tables
only when missing for a fresh database. Flyway records successful execution in
`flyway_schema_history`. Do not manually rerun an already recorded migration.

There is no Flutter repository in this workspace, so Flutter API clients,
Riverpod providers, UI states, and Flutter tests are not included here.
