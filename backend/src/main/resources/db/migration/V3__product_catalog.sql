-- product: каталог кредитных продуктов без залога. Ставки в процентах, единица — rate_unit.
CREATE TABLE product.credit_products (
    code                    VARCHAR(32)   PRIMARY KEY,
    name                    VARCHAR(255)  NOT NULL,
    description             TEXT,
    min_amount              NUMERIC(19,2) NOT NULL,
    max_amount              NUMERIC(19,2) NOT NULL,
    min_term_months         INTEGER       NOT NULL,
    max_term_months         INTEGER       NOT NULL,
    rate_unit               VARCHAR(20)   NOT NULL CHECK (rate_unit IN ('PERCENT_PER_YEAR', 'PERCENT_PER_DAY')),
    base_rate               NUMERIC(6,2)  NOT NULL,
    rate_with_subscription  NUMERIC(6,2),
    funding_modes           VARCHAR(16)[] NOT NULL,
    repayment_types         VARCHAR(16)[] NOT NULL,
    min_business_age_months INTEGER       NOT NULL DEFAULT 0,
    sort_order              INTEGER       NOT NULL,
    active                  BOOLEAN       NOT NULL DEFAULT TRUE,
    CHECK (min_amount > 0 AND min_amount <= max_amount),
    CHECK (min_term_months > 0 AND min_term_months <= max_term_months),
    CHECK (min_business_age_months >= 0)
);

INSERT INTO product.credit_products
    (code, name, description, min_amount, max_amount, min_term_months, max_term_months,
     rate_unit, base_rate, rate_with_subscription, funding_modes, repayment_types,
     min_business_age_months, sort_order)
VALUES
    ('LUMP_SUM_LOAN', 'Кредит одной суммой',
     'Вся сумма сразу, фиксированный график платежей',
     300000.00, 30000000.00, 12, 60,
     'PERCENT_PER_YEAR', 23.50, 14.00, '{LUMP_SUM}', '{ANNUITY}', 12, 1),
    ('CREDIT_LINE', 'Возобновляемая кредитная линия',
     'Деньги частями в пределах лимита, лимит восстанавливается после погашения',
     300000.00, 30000000.00, 1, 12,
     'PERCENT_PER_YEAR', 23.50, 14.50, '{TRANCHES,ON_DEMAND}', '{INTEREST_ONLY,ANNUITY}', 12, 2),
    ('OVERDRAFT', 'Овердрафт для бизнеса',
     'Короткие транши до 60–90 дней на кассовые разрывы',
     50000.00, 20000000.00, 1, 3,
     'PERCENT_PER_YEAR', 26.00, 13.00, '{ON_DEMAND}', '{INTEREST_ONLY}', 0, 3),
    ('GAMMA_START', 'Гамма-Старт',
     'Для нового бизнеса с первого дня регистрации, без залога и финансовых документов',
     50000.00, 1000000.00, 1, 12,
     'PERCENT_PER_DAY', 0.30, 0.15, '{TRANCHES,ON_DEMAND}', '{INTEREST_ONLY}', 0, 4);
