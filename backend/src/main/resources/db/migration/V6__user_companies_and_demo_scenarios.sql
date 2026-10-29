-- identity: связь пользователей и компаний; demo_scenario — подпись в переключателе компании.
-- Демо-клиент получает компании по сценариям.
CREATE TABLE identity.user_companies (
    user_id       UUID         NOT NULL REFERENCES identity.users (id),
    company_id    UUID         NOT NULL REFERENCES identity.companies (id),
    demo_scenario VARCHAR(128),
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, company_id)
);

CREATE INDEX user_companies_company_idx ON identity.user_companies (company_id);

-- Отдельных демо-клиентов заменяет один клиент с компаниями по сценариям.
DELETE FROM identity.users WHERE login IN ('client-start', 'client-liquidating');
DELETE FROM identity.companies WHERE id IN ('9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64', 'c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13');

-- Индекс и CHECK удаляются вместе с колонкой
ALTER TABLE identity.users DROP COLUMN company_id;

INSERT INTO identity.companies
    (id, inn, ogrn, business_type, name, registration_date, status, okved, region)
VALUES
    -- Ручная проверка: просрочка 45 дней (мягкий фактор 31–60)
    ('118ffe67-739c-4053-94df-9024ddfa44a5', '526218493785', '322527500018460', 'IP',
     'ИП Морозов Дмитрий Олегович', '2022-09-12', 'ACTIVE', '49.41', 'Нижегородская область'),
    -- Отказ: просрочка 95 дней (стоп-фактор больше 60)
    ('bd88f8b9-b915-49ff-ab95-85658a48d64b', '6673482158', '1196658019326', 'LLC',
     'ООО «Альтаир»', '2019-11-05', 'ACTIVE', '46.90', 'Свердловская область'),
    -- Предварительное одобрение: чистая история, крупная сумма
    ('21a1be18-cb82-4fed-b463-8f7f5059fb6e', '5406217388', '1155476023812', 'LLC',
     'ООО «ТехноПром»', '2015-04-22', 'ACTIVE', '25.62', 'Новосибирская область');

INSERT INTO identity.user_companies (user_id, company_id, demo_scenario, sort_order)
VALUES
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', '3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90', 'Одобрение', 1),
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', '118ffe67-739c-4053-94df-9024ddfa44a5', 'Ручная проверка', 2),
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', 'bd88f8b9-b915-49ff-ab95-85658a48d64b', 'Отказ: просрочка', 3),
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', '21a1be18-cb82-4fed-b463-8f7f5059fb6e', 'Предварительное одобрение', 4);
