-- application: документы заявки. Содержимое — в отдельной таблице, чтобы список документов не читал файлы.
CREATE TABLE application.documents (
    id              UUID         PRIMARY KEY,
    application_id  UUID         NOT NULL REFERENCES application.applications (id),
    info_request_id UUID         REFERENCES application.info_requests (id),
    document_type   VARCHAR(32)  NOT NULL CHECK (document_type IN ('FINANCIAL_STATEMENT', 'BANK_STATEMENT', 'CHARTER', 'OTHER')),
    file_name       VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(64)  NOT NULL,
    size_bytes      BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256          VARCHAR(64)  NOT NULL,
    uploaded_by     UUID         NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX documents_application_idx ON application.documents (application_id, uploaded_at);

CREATE TABLE application.document_contents (
    document_id UUID  PRIMARY KEY REFERENCES application.documents (id),
    content     BYTEA NOT NULL
);
