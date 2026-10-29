-- Круг обработки заявки (устаревшие события конвейера игнорируются) и оптимистичная блокировка кода подписания.
ALTER TABLE application.applications ADD COLUMN processing_round INTEGER NOT NULL DEFAULT 0;
UPDATE application.applications SET processing_round = 1 WHERE submitted_at IS NOT NULL;

ALTER TABLE application.signing_codes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
