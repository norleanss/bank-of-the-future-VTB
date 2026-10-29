-- scoring: балл с разбивкой по факторам и решения политики (сумма points по результату = score).
CREATE TABLE scoring.scoring_results (
    id             UUID        PRIMARY KEY,
    application_id UUID        NOT NULL,
    score          INTEGER     NOT NULL CHECK (score BETWEEN 0 AND 100),
    grade          VARCHAR(1)  NOT NULL CHECK (grade IN ('A', 'B', 'C', 'D', 'E')),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX scoring_results_application_idx ON scoring.scoring_results (application_id, created_at);

CREATE TABLE scoring.scoring_factors (
    scoring_result_id UUID         NOT NULL REFERENCES scoring.scoring_results (id),
    position          INTEGER      NOT NULL,
    factor            VARCHAR(32)  NOT NULL,
    input_value       VARCHAR(64),
    points            INTEGER      NOT NULL,
    max_points        INTEGER      NOT NULL,
    explanation       VARCHAR(500) NOT NULL,
    PRIMARY KEY (scoring_result_id, position)
);

CREATE TABLE scoring.decisions (
    id                UUID          PRIMARY KEY,
    application_id    UUID          NOT NULL,
    enrichment_id     UUID          NOT NULL,
    scoring_result_id UUID          REFERENCES scoring.scoring_results (id),
    outcome           VARCHAR(16)   NOT NULL
        CHECK (outcome IN ('APPROVE', 'COUNTER_OFFER', 'NEED_INFO', 'MANUAL_REVIEW', 'REJECT')),
    reason_codes      VARCHAR(64)[] NOT NULL,
    missing_fields    VARCHAR(64)[] NOT NULL,
    limit_amount      NUMERIC(19,2),
    offered_amount    NUMERIC(19,2),
    preliminary       BOOLEAN,
    decided_by        VARCHAR(16)   NOT NULL CHECK (decided_by IN ('SYSTEM', 'EMPLOYEE')),
    created_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX decisions_application_idx ON scoring.decisions (application_id, created_at);
