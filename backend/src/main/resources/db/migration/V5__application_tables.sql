-- application: заявки и история статусов. Компания и заявитель — id из identity, без внешних ключей между схемами.
CREATE SEQUENCE application.application_number_seq;

CREATE TABLE application.applications (
    id                    UUID          PRIMARY KEY,
    number                VARCHAR(20)   NOT NULL UNIQUE,
    company_id            UUID          NOT NULL,
    applicant_id          UUID          NOT NULL,
    product_code          VARCHAR(32),
    purpose               VARCHAR(32),
    requested_amount      NUMERIC(19,2),
    term_months           INTEGER,
    funding_mode          VARCHAR(16),
    repayment_type        VARCHAR(16),
    annual_revenue        NUMERIC(19,2),
    annual_expenses       NUMERIC(19,2),
    bank_monthly_turnover NUMERIC(19,2),
    monthly_debt_payment  NUMERIC(19,2),
    collateral            BOOLEAN       NOT NULL DEFAULT FALSE,
    subscription          BOOLEAN       NOT NULL DEFAULT FALSE,
    status                VARCHAR(32)   NOT NULL CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'ENRICHMENT', 'SCORING', 'NEED_INFO', 'MANUAL_REVIEW',
        'APPROVED', 'COUNTER_OFFER', 'PENDING_CONFIRMATION', 'OFFER_ACCEPTED',
        'SIGNED', 'DISBURSED', 'REJECTED', 'OFFER_DECLINED', 'CANCELLED')),
    version               BIGINT        NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    submitted_at          TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CHECK (requested_amount IS NULL OR requested_amount > 0),
    CHECK (term_months IS NULL OR term_months > 0)
);

CREATE INDEX applications_company_created_idx ON application.applications (company_id, created_at DESC);
CREATE INDEX applications_status_idx ON application.applications (status);

-- Порядок записей — по id: у переходов может совпадать время.
CREATE TABLE application.status_history (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id UUID         NOT NULL REFERENCES application.applications (id),
    from_status    VARCHAR(32),
    to_status      VARCHAR(32)  NOT NULL,
    actor_type     VARCHAR(16)  NOT NULL CHECK (actor_type IN ('CLIENT', 'SYSTEM', 'EMPLOYEE')),
    actor_id       UUID,
    comment        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX status_history_application_idx ON application.status_history (application_id, id);
