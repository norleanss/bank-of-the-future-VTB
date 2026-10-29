-- Бэк-офис: с какого момента заявка в текущем статусе (порядок очередей) и решения сотрудников в журнале решений.
ALTER TABLE application.applications ADD COLUMN status_changed_at TIMESTAMPTZ;
UPDATE application.applications a
SET status_changed_at = (SELECT max(h.created_at) FROM application.status_history h WHERE h.application_id = a.id);
ALTER TABLE application.applications ALTER COLUMN status_changed_at SET NOT NULL;

CREATE INDEX applications_queue_idx ON application.applications (status, status_changed_at);

-- У решения сотрудника нет проверки источников
ALTER TABLE scoring.decisions ALTER COLUMN enrichment_id DROP NOT NULL;
ALTER TABLE scoring.decisions ADD COLUMN employee_id UUID;
ALTER TABLE scoring.decisions ADD COLUMN comment VARCHAR(2000);
ALTER TABLE scoring.decisions ADD CONSTRAINT decisions_author_check CHECK (
    (decided_by = 'SYSTEM' AND enrichment_id IS NOT NULL AND employee_id IS NULL)
    OR (decided_by = 'EMPLOYEE' AND employee_id IS NOT NULL));
