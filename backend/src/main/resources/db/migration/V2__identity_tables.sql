-- identity: компании клиентов (данные как в ЕГРЮЛ/ЕГРИП) и пользователи.
CREATE TABLE identity.companies (
    id                UUID         PRIMARY KEY,
    inn               VARCHAR(12)  NOT NULL UNIQUE,
    ogrn              VARCHAR(15)  NOT NULL UNIQUE,
    business_type     VARCHAR(8)   NOT NULL CHECK (business_type IN ('IP', 'LLC')),
    name              VARCHAR(255) NOT NULL,
    registration_date DATE         NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'LIQUIDATING', 'LIQUIDATED')),
    okved             VARCHAR(16),
    region            VARCHAR(128),
    CHECK ((business_type = 'LLC' AND length(inn) = 10 AND length(ogrn) = 13)
        OR (business_type = 'IP' AND length(inn) = 12 AND length(ogrn) = 15))
);

-- login — ключ демо-пользователя для входа без пароля
CREATE TABLE identity.users (
    id         UUID         PRIMARY KEY,
    login      VARCHAR(64)  NOT NULL UNIQUE,
    full_name  VARCHAR(255) NOT NULL,
    role       VARCHAR(16)  NOT NULL CHECK (role IN ('CLIENT', 'UNDERWRITER')),
    company_id UUID         REFERENCES identity.companies (id),
    -- у клиента всегда есть компания, у сотрудника банка — нет
    CHECK ((role = 'CLIENT') = (company_id IS NOT NULL))
);

CREATE INDEX users_company_id_idx ON identity.users (company_id);
