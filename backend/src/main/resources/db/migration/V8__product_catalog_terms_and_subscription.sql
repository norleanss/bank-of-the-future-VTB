-- product: сроки кредита или транша в своих единицах, срок договора, автопродление и условия подписки.
-- Тексты подписки совпадают с примером GET /products в openapi.yaml.
ALTER TABLE product.credit_products RENAME COLUMN min_term_months TO min_term;
ALTER TABLE product.credit_products RENAME COLUMN max_term_months TO max_term;

ALTER TABLE product.credit_products
    ADD COLUMN term_kind                  VARCHAR(8)   NOT NULL DEFAULT 'TRANCHE' CHECK (term_kind IN ('LOAN', 'TRANCHE')),
    ADD COLUMN term_unit                  VARCHAR(8)   NOT NULL DEFAULT 'MONTHS' CHECK (term_unit IN ('MONTHS', 'DAYS')),
    ADD COLUMN max_term_with_subscription INTEGER,
    ADD COLUMN agreement_term_months      INTEGER,
    ADD COLUMN auto_renewal               BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN subscription_description   TEXT,
    ADD COLUMN subscription_fee_percent   NUMERIC(5,2);

ALTER TABLE product.credit_products ALTER COLUMN term_kind DROP DEFAULT;
ALTER TABLE product.credit_products ALTER COLUMN term_unit DROP DEFAULT;
ALTER TABLE product.credit_products
    ADD CONSTRAINT credit_products_subscription_term_check
        CHECK (max_term_with_subscription IS NULL OR max_term_with_subscription >= max_term);

UPDATE product.credit_products
SET term_kind = 'LOAN', term_unit = 'MONTHS', min_term = 12, max_term = 60,
    max_term_with_subscription = NULL, agreement_term_months = NULL, auto_renewal = FALSE,
    subscription_description = 'Ставка от 14% вместо 23,5%: подписка вместе с «Защитой бизнеса». Без комиссии',
    subscription_fee_percent = NULL
WHERE code = 'LUMP_SUM_LOAN';

UPDATE product.credit_products
SET term_kind = 'TRANCHE', term_unit = 'MONTHS', min_term = 1, max_term = 6,
    max_term_with_subscription = 12, agreement_term_months = 18, auto_renewal = TRUE,
    subscription_description = 'Ставка от 14,5% вместо 23,5% и транши до 12 месяцев вместо 6. Без комиссии',
    subscription_fee_percent = NULL
WHERE code = 'CREDIT_LINE';

UPDATE product.credit_products
SET term_kind = 'TRANCHE', term_unit = 'DAYS', min_term = 1, max_term = 60,
    max_term_with_subscription = 90, agreement_term_months = 12, auto_renewal = TRUE,
    subscription_description = 'Ставка от 13% вместо 26%, транши до 90 дней вместо 60, лимит не снижается при падении оборотов. Комиссия — 1% от суммы, единоразово при выдаче',
    subscription_fee_percent = 1.00
WHERE code = 'OVERDRAFT';

UPDATE product.credit_products
SET term_kind = 'TRANCHE', term_unit = 'MONTHS', min_term = 1, max_term = 6,
    max_term_with_subscription = 12, agreement_term_months = 18, auto_renewal = TRUE,
    subscription_description = 'Ставка 0,15% в день вместо 0,3% и транши до 12 месяцев вместо 6. Без комиссии',
    subscription_fee_percent = NULL
WHERE code = 'GAMMA_START';
