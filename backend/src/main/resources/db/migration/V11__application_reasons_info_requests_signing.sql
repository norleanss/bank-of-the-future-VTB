-- application: причины решения для клиента, запросы сведений и коды подписания договора.
CREATE TABLE application.application_reasons (
    application_id UUID         NOT NULL REFERENCES application.applications (id),
    position       INTEGER      NOT NULL,
    code           VARCHAR(64)  NOT NULL,
    message        VARCHAR(500) NOT NULL,
    PRIMARY KEY (application_id, position)
);

CREATE TABLE application.info_requests (
    id                  UUID          PRIMARY KEY,
    application_id      UUID          NOT NULL REFERENCES application.applications (id),
    message             VARCHAR(2000) NOT NULL,
    requested_fields    VARCHAR(64)[] NOT NULL,
    requested_documents VARCHAR(32)[] NOT NULL,
    created_by          VARCHAR(16)   NOT NULL CHECK (created_by IN ('SYSTEM', 'EMPLOYEE')),
    status              VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'ANSWERED')),
    response            VARCHAR(2000),
    created_at          TIMESTAMPTZ   NOT NULL,
    answered_at         TIMESTAMPTZ
);

-- Открытый запрос у заявки один
CREATE UNIQUE INDEX info_requests_one_open_idx ON application.info_requests (application_id) WHERE status = 'OPEN';

-- Код хранится только хешем; действующий код у заявки один
CREATE TABLE application.signing_codes (
    id             UUID        PRIMARY KEY,
    application_id UUID        NOT NULL REFERENCES application.applications (id),
    code_hash      VARCHAR(64) NOT NULL,
    attempts       INTEGER     NOT NULL CHECK (attempts >= 0),
    status         VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'USED', 'SUPERSEDED', 'BLOCKED')),
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX signing_codes_one_active_idx ON application.signing_codes (application_id) WHERE status = 'ACTIVE';
