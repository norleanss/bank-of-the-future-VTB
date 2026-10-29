-- enrichment: данные ЕГРЮЛ, ФНС и БКИ по заявке — профиль компании и ответы источников как есть.
CREATE TABLE enrichment.enrichments (
    id                   UUID          PRIMARY KEY,
    application_id       UUID          NOT NULL,
    inn                  VARCHAR(12),
    company_found        BOOLEAN       NOT NULL,
    company_name         VARCHAR(255),
    ogrn                 VARCHAR(15),
    business_type        VARCHAR(8),
    company_status       VARCHAR(16),
    registration_date    DATE,
    okved                VARCHAR(16),
    region               VARCHAR(255),
    tax_debt_amount      NUMERIC(19,2),
    active_credits_count INTEGER,
    total_debt_amount    NUMERIC(19,2),
    max_overdue_days_12m INTEGER,
    created_at           TIMESTAMPTZ   NOT NULL
);

CREATE INDEX enrichments_application_idx ON enrichment.enrichments (application_id, created_at);

CREATE TABLE enrichment.source_results (
    id            UUID          PRIMARY KEY,
    enrichment_id UUID          NOT NULL REFERENCES enrichment.enrichments (id),
    source        VARCHAR(8)    NOT NULL CHECK (source IN ('EGRUL', 'FNS', 'BKI')),
    status        VARCHAR(16)   NOT NULL CHECK (status IN ('OK', 'NOT_FOUND', 'ERROR', 'TIMEOUT')),
    attempts      INTEGER       NOT NULL CHECK (attempts > 0),
    http_status   INTEGER,
    raw           JSONB,
    error_message VARCHAR(1000),
    requested_at  TIMESTAMPTZ   NOT NULL,
    received_at   TIMESTAMPTZ
);

CREATE INDEX source_results_enrichment_idx ON enrichment.source_results (enrichment_id);
