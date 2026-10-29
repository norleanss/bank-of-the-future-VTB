-- Второй клиент со своей компанией — для проверок «чужая компания / чужая заявка → 403».
-- Только в тестах; повторный запуск безопасен.
INSERT INTO identity.companies (id, inn, ogrn, business_type, name, registration_date, status, okved, region)
VALUES ('183a6eb2-3b32-4f2b-a9c9-27a6aa5d3665', '7819034566', '1207800512370', 'LLC',
        'ООО «Чужая компания»', '2020-02-10', 'ACTIVE', '62.01', 'Санкт-Петербург')
ON CONFLICT DO NOTHING;

INSERT INTO identity.users (id, login, full_name, role)
VALUES ('82368526-2f99-4c29-aa04-237d7b930a44', 'other-client', 'Пётр Сидоров', 'CLIENT')
ON CONFLICT DO NOTHING;

INSERT INTO identity.user_companies (user_id, company_id, demo_scenario, sort_order)
VALUES ('82368526-2f99-4c29-aa04-237d7b930a44', '183a6eb2-3b32-4f2b-a9c9-27a6aa5d3665', NULL, 1)
ON CONFLICT DO NOTHING;
