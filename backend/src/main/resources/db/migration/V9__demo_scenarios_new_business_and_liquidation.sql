-- Ещё два демо-сценария: новый бизнес младше 12 месяцев (подбор предлагает Гамма-Старт и овердрафт)
-- и компания в процессе ликвидации (стоп-фактор).
INSERT INTO identity.companies
    (id, inn, ogrn, business_type, name, registration_date, status, okved, region)
VALUES
    ('9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64', '165809341759', '326160000042815', 'IP',
     'ИП Соколова Анна Викторовна', '2026-07-15', 'ACTIVE', '56.10', 'Республика Татарстан'),
    ('c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13', '7724185366', '1187746182733', 'LLC',
     'ООО «Вектор-Строй»', '2018-03-20', 'LIQUIDATING', '41.20', 'Москва');

INSERT INTO identity.user_companies (user_id, company_id, demo_scenario, sort_order)
VALUES
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', '9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64', 'Гамма-Старт', 5),
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', 'c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13', 'Отказ: ликвидация', 6);
