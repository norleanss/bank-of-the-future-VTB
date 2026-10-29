-- Вид срока в предложении (срок кредита или транша — от него зависит, как описать условия) и совет клиенту к причине.
-- Текст причины — без ограничения длины: у решения сотрудника это его комментарий (до 2000 символов по контракту).
ALTER TABLE application.offers ADD COLUMN term_kind VARCHAR(8);
UPDATE application.offers SET term_kind = CASE WHEN product_code = 'LUMP_SUM_LOAN' THEN 'LOAN' ELSE 'TRANCHE' END;
ALTER TABLE application.offers ALTER COLUMN term_kind SET NOT NULL;
ALTER TABLE application.offers ADD CONSTRAINT offers_term_kind_check CHECK (term_kind IN ('LOAN', 'TRANCHE'));

ALTER TABLE application.application_reasons ALTER COLUMN message TYPE TEXT;
ALTER TABLE application.application_reasons ADD COLUMN advice TEXT;
