-- Демо-компании и пользователи. ИНН и ОГРН с валидными контрольными цифрами, моки — mocks/wiremock/mappings;
-- id клиента и «Северного ветра» совпадают с примерами в openapi.yaml.
INSERT INTO identity.companies
    (id, inn, ogrn, business_type, name, registration_date, status, okved, region)
VALUES
    -- Бизнес старше года: основной сценарий
    ('3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90', '7841256395', '1217800123452', 'LLC',
     'ООО «Северный ветер»', '2021-05-14', 'ACTIVE', '47.11', 'Санкт-Петербург'),
    -- Новый бизнес: подбор предлагает Гамма-Старт и овердрафт
    ('9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64', '165809341759', '326160000042815', 'IP',
     'ИП Соколова Анна Викторовна', '2026-07-15', 'ACTIVE', '56.10', 'Республика Татарстан'),
    -- Ликвидируется: стоп-фактор
    ('c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13', '7724185366', '1187746182733', 'LLC',
     'ООО «Вектор-Строй»', '2018-03-20', 'LIQUIDATING', '41.20', 'Москва');

INSERT INTO identity.users (id, login, full_name, role, company_id)
VALUES
    ('7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f', 'client', 'Иван Петров', 'CLIENT',
     '3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90'),
    ('2a7c9e1f-4b3d-4d8a-9f6e-1c5b8a3d7e20', 'client-start', 'Анна Соколова', 'CLIENT',
     '9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64'),
    ('e5b1d8a3-6f2c-4e9b-a7d4-3c8f1b6e0a95', 'client-liquidating', 'Сергей Волков', 'CLIENT',
     'c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13'),
    ('5c3e7a9d-1f4b-4b2e-8d6a-9e2c4f7b1a38', 'underwriter', 'Мария Смирнова', 'UNDERWRITER', NULL);
