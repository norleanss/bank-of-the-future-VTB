# Backend — кредитный конвейер МСБ

Модульный монолит на Spring Boot 4.1 и Java 21.

Полный Docker-стенд теперь включает frontend (production-сборка React в Nginx),
backend, PostgreSQL и WireMock. Для запуска всего проекта достаточно Docker:
`docker compose up -d --build --wait` из корня, кабинет — http://127.0.0.1:5173.
Единая инструкция, параметры и сквозные проверки — в [корневом README](../README.md).
Ниже — отдельная разработка и устройство backend.

## Стек и версии

Версии — из разрешённых зависимостей сборки (`./mvnw dependency:list`), образов `docker-compose.yml` и Maven Wrapper.

| Технология | Версия | Для чего |
|---|---|---|
| Java (Eclipse Temurin в образе) | 21 | язык и среда выполнения (LTS) |
| Maven (Wrapper) | 3.9.11 | сборка, `./mvnw` |
| Spring Boot | 4.1.1 | основа приложения, автоконфигурация, Actuator |
| Spring Framework | 7.0.9 | Web MVC, транзакции, события, планировщик, повторы (`RetryTemplate`) |
| Spring Security | 7.1.1 | вход по JWT (OAuth2 Resource Server), роли, CORS |
| Spring Modulith | 2.1.1 | границы модулей, события между модулями с журналом в базе |
| Hibernate ORM | 7.4.5.Final | JPA, оптимистичная блокировка |
| Jackson | 3.1.5 (аннотации 2.21) | JSON |
| Jackson 2 | 2.21.5 | транзитивно через springdoc/swagger-core, только для Swagger UI; приложение использует Jackson 3 |
| Flyway | 12.4.0 | миграции схемы |
| PostgreSQL JDBC | 42.7.13 | драйвер базы |
| PostgreSQL (образ `postgres:17.6-alpine`) | 17.6 | база данных |
| Apache Tomcat (встроенный) | 11.0.24 | HTTP-сервер |
| springdoc-openapi | 3.1.0 | Swagger UI по контракту |
| openapi-generator (Maven-плагин) | 7.22.0 | интерфейсы контроллеров и DTO из `docs/api/openapi.yaml` |
| WireMock (образ и тесты) | 3.9.1 | моки ЕГРЮЛ, ФНС, БКИ |
| Testcontainers | 2.0.5 | PostgreSQL в интеграционных тестах |
| JUnit Jupiter | 6.0.3 | тесты |
| AssertJ / Mockito / Awaitility | 3.27.7 / 5.23.0 / 4.3.0 | проверки, заглушки, ожидание асинхронной обработки |

## Что нужно на компьютере

- Java 21 (например, Eclipse Temurin). Если по умолчанию стоит другая версия: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (macOS)
- Docker Desktop (нужен и для тестов — они поднимают PostgreSQL через Testcontainers)
- Maven не нужен: в репозитории есть Maven Wrapper (`./mvnw`)

## Первый запуск

1. Поднять базу и моки внешних источников (из корня репозитория):

   ```bash
   docker compose up -d postgres wiremock
   ```

2. Собрать и проверить (из папки `backend`):

   ```bash
   ./mvnw verify
   ```

3. Запустить приложение из IDE (класс `CreditConveyorApplication`) или командой:

   ```bash
   DB_URL=jdbc:postgresql://localhost:55432/credit_conveyor ./mvnw spring-boot:run
   ```

Весь стенд целиком, включая бэкенд в контейнере, поднимается из корня репозитория:

```bash
docker compose up -d --build --wait
```

В PowerShell для локального запуска backend: задайте
`$env:DB_URL='jdbc:postgresql://localhost:55432/credit_conveyor'`, затем выполните
`.\mvnw.cmd spring-boot:run` из папки `backend`. Это порт базы из Compose;
если `POSTGRES_PORT` изменён в корневом `.env`, используйте соответствующий порт.

## Тестовые учётные записи

Пароля нет: вход — `POST /api/v1/auth/demo-login` с ролью, ответ — JWT (подробнее — «Как проверить»). Роли и доступ к компаниям проверяются по-настоящему. Все данные синтетические, создаются миграциями при первом старте.

| Пользователь | Роль | Тело `demo-login` | Что доступно |
|---|---|---|---|
| Иван Петров | демо-клиент | `{"role": "CLIENT"}` | 6 компаний — по одной на демо-сценарий, выбор переключателем «Демо-режим: выберите компанию» (`GET /companies`); заявки только этих компаний |
| Мария Смирнова | сотрудник банка (андеррайтер) | `{"role": "UNDERWRITER"}` | бэк-офис: очереди, карточка заявки, решения, запросы сведений; клиентские разделы — `403` |

Компании демо-клиента (`demoScenario` — подпись в переключателе):

| Подпись | Компания | ИНН | `companyId` | Особенность в моках |
|---|---|---|---|---|
| Одобрение | ООО «Северный ветер» | 7841256395 | `3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90` | чистая история, бизнес с 2021 |
| Ручная проверка | ИП Морозов Д. О. | 526218493785 | `118ffe67-739c-4053-94df-9024ddfa44a5` | просрочка 45 дней |
| Отказ: просрочка | ООО «Альтаир» | 6673482158 | `bd88f8b9-b915-49ff-ab95-85658a48d64b` | просрочка 95 дней |
| Предварительное одобрение | ООО «ТехноПром» | 5406217388 | `21a1be18-cb82-4fed-b463-8f7f5059fb6e` | чистая история, бизнес с 2015 |
| Гамма-Старт | ИП Соколова А. В. | 165809341759 | `9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64` | бизнесу 3 месяца |
| Отказ: ликвидация | ООО «Вектор-Строй» | 7724185366 | `c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13` | статус `LIQUIDATING` |

Для каждой компании есть моки ЕГРЮЛ, ФНС и БКИ в `mocks/wiremock/mappings`; любой другой ИНН ЕГРЮЛ не находит.

## Демо-сценарии для проверки

Пошагово — что выбрать, что ввести и что ожидать по каждой демо-компании, сценарии андеррайтера, запуск коллекции Postman и автотестов — [`docs/test-scenarios.md`](../docs/test-scenarios.md). Как считаются баллы, лимиты и условия в этих сценариях — [`docs/scoring.md`](../docs/scoring.md#демо-сценарии).

## Как проверить

Вход — `POST /auth/demo-login` с ролью, без пароля. Ответ — JWT, дальше он передаётся в заголовке `Authorization: Bearer <токен>` (в Swagger UI — кнопка **Authorize**). Без токена все эндпоинты, кроме входа, `health`, Swagger UI и самого контракта (`/openapi.yaml`), отвечают `401`. Сгенерированная из кода спецификация springdoc (`/v3/api-docs`) закрыта: Swagger UI показывает контракт `docs/api/openapi.yaml`.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/demo-login -H 'Content-Type: application/json' \
  -d '{"role":"CLIENT"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -s localhost:8080/api/v1/companies -H "Authorization: Bearer $TOKEN"
curl -s -X POST localhost:8080/api/v1/applications -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"companyId":"3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90","productCode":"LUMP_SUM_LOAN","purpose":"EQUIPMENT",
       "requestedAmount":"500000.00","term":24,"termUnit":"MONTHS","fundingMode":"LUMP_SUM",
       "financials":{"annualRevenue":"20000000.00","annualExpenses":"14000000.00","monthlyDebtPayment":"50000.00"}}'
# затем POST /applications/{id}/submit — ответ сразу SUBMITTED, решение (здесь APPROVED) через 1–2 секунды:
# GET /applications/{id}, POST …/offer/accept, POST …/signing/request-code, POST …/signing/confirm, GET …/history
```

Пользователи и компании — «Тестовые учётные записи», что ввести и что получится — «Демо-сценарии для проверки».

**Доступ.** Клиент видит и меняет только заявки своих компаний (`identity.user_companies`, проверка — компонент `CompanyAccess`), иначе `403`; заявки клиента недоступны андеррайтеру, бэк-офис — клиенту. Роли проверяются аннотацией `@PreAuthorize` на контроллерах и, для страховки, правилами по URL.

**Заявка.** Статус меняется только машиной состояний модуля `application` по таблице переходов (`StatusTransitions`); каждый переход пишется в историю, недопустимое действие — `409`, одновременное изменение одной заявки — тоже `409` (оптимистичная блокировка). Кнопки клиента — поле `availableActions`, подсказка — `nextStepHint`. Предложение действует 7 дней (`app.offer.validity`): после срока заявка переходит в `OFFER_EXPIRED` — при чтении заявки и фоновой задачей (`app.offer.expiry-check-interval`), принять просроченное предложение нельзя (`409`, `type …/offer-expired`). `PATCH` — JSON Merge Patch: непереданное поле не меняется, `null` очищает, `financials` сливается по полям. В ответах поля без значения не передаются.

**Обработка заявки — асинхронная.** `submit` проверяет анкету и условия продукта (сумма, срок с учётом подписки, возраст бизнеса) и сразу отвечает `202` со статусом `SUBMITTED`. Дальше в фоне, событиями между модулями: `ENRICHMENT` — запросы к ЕГРЮЛ, ФНС и БКИ (моки WireMock; таймауты и повторы — `integrations.*`) → `SCORING` — базовая политика команды (`docs/scoring.md`, значения — `app.scoring`) → решение: `APPROVED`, `COUNTER_OFFER`, `NEED_INFO`, `MANUAL_REVIEW` или `REJECTED` с причинами в `reasons`. Обычно это 1–2 секунды; статус читать через `GET /applications/{id}`. События хранятся в базе (схема `events`): если бэкенд перезапустится посреди обработки, она продолжится после старта. Непредвиденная ошибка обработки — заявка на ручной проверке (`PROCESSING_ERROR`).

`NEED_INFO`: клиент дозаполняет запрошенные поля через `PATCH` (продукт, сумму, срок и подписку после отправки менять нельзя), загружает запрошенные документы и отвечает на запрос с `documentIds` — заявка проверяется заново (если сведения запросил андеррайтер — возвращается к нему). Подписание — код из `signing/request-code` (в демо он в ответе, `demoCode`; живёт 5 минут, 3 попытки — `app.signing.*`) → `signing/confirm` → `SIGNED`, затем система моделирует выдачу → `DISBURSED`.

**Бэк-офис** (роль `UNDERWRITER`): очереди `GET /backoffice/applications?queue=REVIEW|CONFIRMATION|ALL` (дольше ждущие первыми), карточка `GET /backoffice/applications/{id}` — анкета, компания, ответы ЕГРЮЛ/ФНС/БКИ, балл с разбивкой по факторам, причины, история с именами сотрудников, запросы сведений, документы. Решение `POST …/decision`: `APPROVE` (без условий — сумма и срок заявки, ставка каталога; оффер сотрудника не предварительный), `COUNTER_OFFER` (сумма и комментарий обязательны, можно другой продукт), `REJECT` (комментарий обязателен — клиент его видит); условия проверяются по продукту (иначе `400`), лимит по финансам сотрудник может превысить. Из `PENDING_CONFIRMATION` `APPROVE` подтверждает принятые клиентом условия → `OFFER_ACCEPTED`. Запрос сведений — `POST …/info-requests` (поля и документы) → `NEED_INFO`. Решения сотрудника пишутся в журнал решений (`scoring.decisions`, `decided_by = EMPLOYEE`). Сценарии — [`docs/test-scenarios.md`](../docs/test-scenarios.md#сценарии-андеррайтера).

**Уведомления.** Каждая смена статуса публикуется событием; модуль `notification` создаёт уведомление клиенту (отправка, запрос сведений, ручная проверка, решение, подтверждение условий, истечение предложения, подписание, выдача). `GET /notifications`, `POST /notifications/{id}/read`, поток Server-Sent Events `GET /notifications/stream?access_token=<токен>` — `ping` при подключении и раз в 30 секунд (`app.notifications.*`). Проверить поток:

```bash
curl -N "localhost:8080/api/v1/notifications/stream?access_token=$TOKEN"
```

**Документы.** `POST /applications/{id}/documents` (multipart `file` + `documentType`) — когда банк их запросил (`NEED_INFO`). Формат — PDF, JPG, PNG, проверяется по содержимому файла; до 10 МБ и 20 документов на заявку (`app.documents.*`); хранятся в PostgreSQL. Скачивание — `GET /applications/{id}/documents/{documentId}/content` (клиент, своя заявка) и `GET /backoffice/applications/{id}/documents/{documentId}/content` (андеррайтер).

**Подбор продукта** (`ProductMatcher`, правила — `ProductRules`): сумма, срок, способ получения и возраст бизнеса проверяются по условиям продукта — каждое несоответствие возвращается причиной. Подписку клиент не передаёт: `suitable` — подходит на базовых условиях, `requiresSubscription` — только с подпиской (например, транш овердрафта до 90 дней вместо 60), в причинах — что даёт подписка. Сроки в разных единицах сравниваются из расчёта 30 дней в месяце. Цель кредита не исключает продукт, а задаёт порядок рекомендации; бизнесу младше 12 месяцев первым предлагается Гамма-Старт. Рекомендован первый подходящий.

## Адреса

| Что | Адрес |
|---|---|
| API | http://localhost:8080/api/v1 |
| Swagger UI (контракт с примерами) | http://localhost:8080/api/v1/swagger-ui.html |
| Проверка здоровья | http://localhost:8080/api/v1/actuator/health |
| Моки ЕГРЮЛ / ФНС / БКИ (WireMock) | http://localhost:8089 |
| PostgreSQL | localhost:55432, база `credit_conveyor`, пользователь и пароль `credit` |

**Swagger UI на другом порту.** «Try it out» отправляет запросы на сервер из списка **Servers** контракта — `http://localhost:8080/api/v1`. Если бэкенд опубликован на другом порту (`BACKEND_PORT`), запросы из Swagger UI на него не попадут: для проверки в Swagger UI оставьте `BACKEND_PORT=8080` (освободив порт), а на другом порту пользуйтесь curl или коллекцией Postman с переменной `baseUrl` (см. [`docs/test-scenarios.md`](../docs/test-scenarios.md#коллекция-postman)).

## Как устроен код

```
ru.creditconveyor
├── CreditConveyorApplication   точка входа
├── api            (генерируется) интерфейсы контроллеров и DTO из docs/api/openapi.yaml
├── identity       демо-вход, пользователи и роли, компании клиента
├── product        каталог продуктов и подбор
├── application    заявки, машина состояний, офферы, подписание, выдача
├── enrichment     запросы к ЕГРЮЛ, ФНС, БКИ
├── scoring        скоринг и кредитная политика
├── notification   уведомления и поток SSE
└── backoffice     очереди и ручные решения андеррайтера
```

**Модули.** Каждый прямой подпакет — модуль Spring Modulith. Модуль обращается к другому только через классы в его корневом пакете; всё во вложенных пакетах — внутреннее. Модули общаются событиями Spring. Тест `ModularityTests` проверяет это при каждой сборке и падает, если граница нарушена. Схема модулей и их событий, диаграмма состояний заявки и модель данных — [`docs/diagrams.md`](../docs/diagrams.md).

**Контракт API.** Единственный источник — `docs/api/openapi.yaml` в корне репозитория. При сборке из него генерируются интерфейсы (`ProductsApi`, `ApplicationsApi`, ...) и DTO с суффиксом `Dto` в `target/generated-sources/openapi`; в репозиторий этот код не коммитится. Контроллер реализует сгенерированный интерфейс:

```java
@RestController
class ProductController implements ProductsApi {
    @Override
    public ResponseEntity<List<ProductDto>> listProducts() { ... }
}
```

Сгенерированные методы по умолчанию отвечают `501 Not Implemented` — так новый эндпоинт из контракта виден сразу, ещё до реализации (сейчас все эндпоинты контракта реализованы). Изменили контракт — пересоберите проект, и компилятор покажет, где код разошёлся со спецификацией. Исключение — поток `GET /notifications/stream`: у операции в контракте `x-internal: true`, генератор её пропускает, а поток отдаёт обработчик на `SseEmitter` в `NotificationController` (сгенерированный метод не может вернуть поток событий).

**База данных.** Схема меняется только миграциями Flyway в `src/main/resources/db/migration` (`V2__...sql`, `V3__...sql`, ...). Hibernate схему не создаёт, только проверяет. У каждого модуля своя схема PostgreSQL.

## Переменные окружения

| Переменная | По умолчанию |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/credit_conveyor` |
| `DB_USER` / `DB_PASSWORD` | `credit` / `credit` |
| `EGRUL_BASE_URL` | `http://localhost:8089/egrul` |
| `FNS_BASE_URL` | `http://localhost:8089/fns` |
| `BKI_BASE_URL` | `http://localhost:8089/bki` |
| `SERVER_PORT` | `8080` |
| `POSTGRES_PORT` / `WIREMOCK_PORT` / `BACKEND_PORT` / `FRONTEND_PORT` | `55432` / `8089` / `8080` / `5173` — порты на хосте в `docker-compose.yml`; поменяйте в `.env`, если заняты. Если меняете порты базы или моков, а бэкенд запускаете не в Docker, передайте ему `DB_URL` и `*_BASE_URL` с новыми портами |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` — с каких адресов фронтенд обращается к API |
| `JWT_SECRET` | пусто — при каждом старте случайный ключ (выданные токены сбрасываются при перезапуске). Если задан — не короче 32 байт (латиницей — 32 символа) |
