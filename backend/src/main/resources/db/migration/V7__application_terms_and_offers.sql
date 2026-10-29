-- application: срок заявки term + term_unit, статус OFFER_EXPIRED, предложения со сроком действия.
ALTER TABLE application.applications RENAME COLUMN term_months TO term;
ALTER TABLE application.applications ADD COLUMN term_unit VARCHAR(8) CHECK (term_unit IN ('MONTHS', 'DAYS'));
UPDATE application.applications SET term_unit = 'MONTHS' WHERE term IS NOT NULL;
ALTER TABLE application.applications
    ADD CONSTRAINT applications_term_pair_check CHECK ((term IS NULL) = (term_unit IS NULL));

ALTER TABLE application.applications DROP CONSTRAINT applications_status_check;
ALTER TABLE application.applications ADD CONSTRAINT applications_status_check CHECK (status IN (
    'DRAFT', 'SUBMITTED', 'ENRICHMENT', 'SCORING', 'NEED_INFO', 'MANUAL_REVIEW',
    'APPROVED', 'COUNTER_OFFER', 'PENDING_CONFIRMATION', 'OFFER_ACCEPTED',
    'SIGNED', 'DISBURSED', 'REJECTED', 'OFFER_DECLINED', 'OFFER_EXPIRED', 'CANCELLED'));

-- Действующее предложение одно; при контрпредложении прежнее — SUPERSEDED.
CREATE TABLE application.offers (
    id                    UUID          PRIMARY KEY,
    application_id        UUID          NOT NULL REFERENCES application.applications (id),
    product_code          VARCHAR(32)   NOT NULL,
    product_name          VARCHAR(255)  NOT NULL,
    amount                NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    term                  INTEGER       NOT NULL CHECK (term > 0),
    term_unit             VARCHAR(8)    NOT NULL CHECK (term_unit IN ('MONTHS', 'DAYS')),
    agreement_term_months INTEGER,
    rate                  NUMERIC(6,2)  NOT NULL,
    rate_unit             VARCHAR(20)   NOT NULL CHECK (rate_unit IN ('PERCENT_PER_YEAR', 'PERCENT_PER_DAY')),
    monthly_payment       NUMERIC(19,2),
    subscription          BOOLEAN       NOT NULL,
    subscription_fee      NUMERIC(19,2),
    preliminary           BOOLEAN       NOT NULL,
    status                VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'SUPERSEDED')),
    expires_at            TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX offers_application_idx ON application.offers (application_id);
-- Для фоновой задачи истечения
CREATE INDEX offers_active_expires_idx ON application.offers (expires_at) WHERE status = 'ACTIVE';
