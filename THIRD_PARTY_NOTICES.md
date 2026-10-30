# Сторонние компоненты

Перечень сторонних компонентов решения «Умный кредитный кабинет» с версиями и лицензиями, а также интеграций, которые в прототипе смоделированы. Решение состоит из веб-интерфейса (`frontend/`), бэкенда (`backend/`), базы PostgreSQL и моков внешних источников на WireMock; всё запускается в Docker (`docker-compose.yml`).

Решение не использует моделей машинного обучения, LLM, внешних датасетов и платных сервисов. Внешние системы банка и государственные реестры не вызываются — они смоделированы (см. «Смоделированные интеграции»), все данные синтетические (см. «Данные»).

## Смоделированные интеграции

Настоящие внешние сервисы не вызываются. Все внешние источники — **моки WireMock** (контейнер `wiremock`, сценарии ответов — `mocks/wiremock/mappings`, адреса — переменные `EGRUL_BASE_URL`, `FNS_BASE_URL`, `BKI_BASE_URL`). Формат ответов придуман командой и не повторяет API реальных сервисов.

| Интеграция | Что моделирует | Путь мока | Что возвращает |
|---|---|---|---|
| ЕГРЮЛ/ЕГРИП (ФНС России) | сведения о регистрации компании или ИП | `GET /egrul/companies/{inn}` | наименование, ОГРН/ОГРНИП, дата регистрации, статус (`ACTIVE`, `LIQUIDATING`…), ОКВЭД, регион; неизвестный ИНН — 404 |
| ФНС — налоговая задолженность | задолженность по налогам и сборам | `GET /fns/companies/{inn}/tax-debts` | сумма задолженности |
| БКИ (бюро кредитных историй) | кредитный отчёт | `GET /bki/companies/{inn}/credit-report` | действующие кредиты, текущий долг, максимальная просрочка за 12 месяцев |

Смоделированы внутри приложения (без внешних систем):

| Что | Как в прототипе |
|---|---|
| Вход клиента и сотрудника банка | демо-вход по роли без пароля (`POST /auth/demo-login`), JWT выпускает сам бэкенд |
| Действующие клиенты банка и их компании | демо-данные в базе (миграции Flyway) |
| Подписание договора | одноразовый код, в демо возвращается в ответе (`demoCode`); без SMS |
| Выдача кредита | смена статуса на `DISBURSED` после подписания; без АБС и платёжных систем |
| Уведомления | только в кабинете (список и поток SSE); без email/SMS/мессенджеров |
| Обороты по счёту в банке | вводит клиент в анкете (`bankMonthlyTurnover`) |

Режим разработки фронтенда без бэкенда (`VITE_API_MODE=mock`, по умолчанию у dev-сервера Vite): весь API имитируется в браузере (`frontend/src/shared/mocks`) — демо-компании, подбор, обработка заявки, решения и документы, состояние хранится в `localStorage`. В Docker-стенде этот режим не используется: сборка идёт с `VITE_API_MODE=http`, кабинет работает с настоящим бэкендом. Для разработки по контракту без бэкенда есть ещё мок-сервер Prism по `docs/api/openapi.yaml` (см. `docs/api/README.md`).

## Данные

Все данные синтетические и созданы командой: 6 демо-компаний демо-клиента (наименования и реквизиты придуманы; у ИНН и ОГРН/ОГРНИП корректные контрольные цифры, чтобы проходить проверку формата; совпадение с реальными организациями не предполагается), демо-пользователи, ответы моков ЕГРЮЛ/ФНС/БКИ, каталог продуктов. Условия продуктов основаны на публичных условиях с сайта банка и демонстрационные. Внешние датасеты не используются.

## Бэкенд

Список зависимостей — из разрешённого дерева сборки Maven (`./mvnw dependency:list`, все области, включая транзитивные); лицензии — из POM-файлов артефактов (поле `<licenses>`, при отсутствии — из родительского POM). Обновляется при изменении `backend/pom.xml`.

### Ключевые компоненты

| Компонент | Версия | Лицензия | Для чего |
|---|---|---|---|
| Eclipse Temurin (OpenJDK) | 21 (`eclipse-temurin:21-jre-jammy`) | GPL-2.0 with Classpath Exception | среда выполнения Java |
| Spring Boot | 4.1.1 | Apache-2.0 | основа приложения, автоконфигурация, Actuator |
| Spring Framework | 7.0.9 | Apache-2.0 | Web MVC, транзакции, события, планировщик, повторы |
| Spring Security | 7.1.1 | Apache-2.0 | вход по JWT (OAuth2 Resource Server), роли, CORS |
| Spring Data JPA | 4.1.1 | Apache-2.0 | репозитории JPA |
| Spring Modulith | 2.1.1 | Apache-2.0 | границы модулей, события с журналом в базе |
| Hibernate ORM | 7.4.5.Final | Apache-2.0 | JPA |
| Hibernate Validator | 9.1.3.Final | Apache-2.0 | Bean Validation |
| Jackson | 3.1.5 (аннотации 2.21; Jackson 2.21.5 — транзитивно через springdoc/swagger-core, только для Swagger UI) | Apache-2.0 | JSON |
| Flyway | 12.4.0 | Apache-2.0 | миграции схемы |
| PostgreSQL JDBC Driver | 42.7.13 | BSD-2-Clause | драйвер базы |
| HikariCP | 7.0.2 | Apache-2.0 | пул соединений |
| Apache Tomcat (встроенный) | 11.0.24 | Apache-2.0 | HTTP-сервер |
| Nimbus JOSE + JWT | 10.9.1 | Apache-2.0 | подпись и проверка JWT |
| springdoc-openapi | 3.1.0 | Apache-2.0 | Swagger UI |
| Swagger UI (WebJar) | 5.32.11 | Apache-2.0 | интерфейс документации API |
| Logback | 1.5.38 | EPL-2.0 / LGPL-2.1 (двойная) | логирование |
| SLF4J | 2.0.18 | MIT | API логирования |
| Micrometer | 1.17.1 | Apache-2.0 | метрики Actuator |

### Инструменты сборки (в образ не входят)

| Компонент | Версия | Лицензия | Для чего |
|---|---|---|---|
| Apache Maven (через Maven Wrapper) | 3.9.11 | Apache-2.0 | сборка |
| Образ `maven:3.9.11-eclipse-temurin-21` | 3.9.11 / JDK 21 | Apache-2.0 / GPL-2.0 with Classpath Exception | стадия сборки в `backend/Dockerfile` |
| openapi-generator-maven-plugin | 7.22.0 | Apache-2.0 | генерация интерфейсов контроллеров и DTO из `docs/api/openapi.yaml` |
| spring-boot-maven-plugin | 4.1.1 | Apache-2.0 | сборка исполняемого JAR |
| maven-resources-plugin | из Spring Boot 4.1.1 | Apache-2.0 | копирование контракта в статические ресурсы |

### Образы Docker

| Образ | Лицензия | Для чего |
|---|---|---|
| `postgres:17.6-alpine` | PostgreSQL License (сам PostgreSQL); базовый образ Alpine Linux — пакеты под своими лицензиями | база данных |
| `wiremock/wiremock:3.9.1` | Apache-2.0 | моки внешних источников |
| `eclipse-temurin:21-jre-jammy` (база образа бэкенда) | GPL-2.0 with Classpath Exception (JDK); Ubuntu 22.04 — пакеты под своими лицензиями | среда выполнения бэкенда |

### Все зависимости

#### Входят в приложение (compile, runtime)

| Артефакт (groupId:artifactId) | Версия | Лицензия | Область |
|---|---|---|---|
| `ch.qos.logback:logback-classic` | 1.5.38 | EPL-2.0 / LGPL-2.1 (двойная) | compile |
| `ch.qos.logback:logback-core` | 1.5.38 | EPL-2.0 / LGPL-2.1 (двойная) | compile |
| `com.fasterxml:classmate` | 1.7.3 | Apache-2.0 | compile |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 | Apache-2.0 | compile |
| `com.fasterxml.jackson.core:jackson-core` | 2.21.5 | Apache-2.0 | compile |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | Apache-2.0 | compile |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.21.5 | Apache-2.0 | compile |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.21.5 | Apache-2.0 | compile |
| `com.nimbusds:nimbus-jose-jwt` | 10.9.1 | Apache-2.0 | compile |
| `com.sun.istack:istack-commons-runtime` | 4.1.2 | EDL-1.0 (BSD-3-Clause) | runtime |
| `com.tngtech.archunit:archunit` | 1.4.2 | Apache-2.0; BSD | runtime |
| `com.zaxxer:HikariCP` | 7.0.2 | Apache-2.0 | compile |
| `commons-logging:commons-logging` | 1.3.6 | Apache-2.0 | compile |
| `io.micrometer:micrometer-commons` | 1.17.1 | Apache-2.0 | compile |
| `io.micrometer:micrometer-core` | 1.17.1 | Apache-2.0 | compile |
| `io.micrometer:micrometer-jakarta9` | 1.17.1 | Apache-2.0 | compile |
| `io.micrometer:micrometer-observation` | 1.17.1 | Apache-2.0 | compile |
| `io.swagger.core.v3:swagger-annotations-jakarta` | 2.2.52 | Apache-2.0 | compile |
| `io.swagger.core.v3:swagger-core-jakarta` | 2.2.52 | Apache-2.0 | compile |
| `io.swagger.core.v3:swagger-models-jakarta` | 2.2.52 | Apache-2.0 | compile |
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | EDL-1.0 (BSD-3-Clause) | compile |
| `jakarta.annotation:jakarta.annotation-api` | 3.0.0 | EPL-2.0 / GPL-2.0 with Classpath Exception (двойная) | compile |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 | runtime |
| `jakarta.persistence:jakarta.persistence-api` | 3.2.0 | EPL-2.0 / EDL-1.0 (двойная) | compile |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | EPL-2.0 / GPL-2.0 with Classpath Exception (двойная) | compile |
| `jakarta.validation:jakarta.validation-api` | 3.1.1 | Apache-2.0 | compile |
| `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.5 | EDL-1.0 (BSD-3-Clause) | compile |
| `net.bytebuddy:byte-buddy` | 1.18.11 | Apache-2.0 | runtime |
| `org.antlr:antlr4-runtime` | 4.13.2 | BSD-3-Clause | compile |
| `org.apache.commons:commons-lang3` | 3.20.0 | Apache-2.0 | compile |
| `org.apache.logging.log4j:log4j-api` | 2.25.5 | Apache-2.0 | compile |
| `org.apache.logging.log4j:log4j-to-slf4j` | 2.25.5 | Apache-2.0 | compile |
| `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | Apache-2.0 | compile |
| `org.apache.tomcat.embed:tomcat-embed-el` | 11.0.24 | Apache-2.0 | compile |
| `org.apache.tomcat.embed:tomcat-embed-websocket` | 11.0.24 | Apache-2.0 | compile |
| `org.aspectj:aspectjweaver` | 1.9.25.1 | EPL-2.0 | compile |
| `org.checkerframework:checker-qual` | 3.55.1 | MIT | runtime |
| `org.eclipse.angus:angus-activation` | 2.0.3 | EDL-1.0 (BSD-3-Clause) | runtime |
| `org.flywaydb:flyway-core` | 12.4.0 | Apache-2.0 | compile |
| `org.flywaydb:flyway-database-postgresql` | 12.4.0 | Apache-2.0 | compile |
| `org.glassfish.jaxb:jaxb-core` | 4.0.9 | EDL-1.0 (BSD-3-Clause) | runtime |
| `org.glassfish.jaxb:jaxb-runtime` | 4.0.9 | EDL-1.0 (BSD-3-Clause) | runtime |
| `org.glassfish.jaxb:txw2` | 4.0.9 | EDL-1.0 (BSD-3-Clause) | runtime |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | CC0-1.0 / BSD-2-Clause (двойная) | runtime |
| `org.hibernate.models:hibernate-models` | 1.1.1 | Apache-2.0 | runtime |
| `org.hibernate.orm:hibernate-core` | 7.4.5.Final | Apache-2.0 | compile |
| `org.hibernate.validator:hibernate-validator` | 9.1.3.Final | Apache-2.0 | compile |
| `org.jboss.logging:jboss-logging` | 3.6.3.Final | Apache-2.0 | compile |
| `org.jmolecules:jmolecules-events` | 2.0.1 | Apache-2.0 | compile |
| `org.jmolecules:jstereotype` | 2.0.1 | Apache-2.0 | compile |
| `org.jspecify:jspecify` | 1.0.1 | Apache-2.0 | compile |
| `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause | runtime |
| `org.slf4j:jcl-over-slf4j` | 2.0.18 | Apache-2.0 | runtime |
| `org.slf4j:jul-to-slf4j` | 2.0.18 | MIT | compile |
| `org.slf4j:slf4j-api` | 2.0.18 | MIT | compile |
| `org.springdoc:springdoc-openapi-starter-common` | 3.1.0 | Apache-2.0 | compile |
| `org.springdoc:springdoc-openapi-starter-webmvc-api` | 3.1.0 | Apache-2.0 | compile |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.1.0 | Apache-2.0 | compile |
| `org.springframework:spring-aop` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-aspects` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-beans` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-context` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-core` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-expression` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-jdbc` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-orm` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-tx` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-web` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework:spring-webmvc` | 7.0.9 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-actuator` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-actuator-autoconfigure` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-autoconfigure` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-data-commons` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-data-jpa` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-flyway` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-health` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-hibernate` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-http-converter` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-jackson` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-jdbc` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-jpa` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-micrometer-metrics` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-micrometer-observation` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-persistence` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-security` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-security-oauth2-resource-server` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-servlet` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-sql` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-actuator` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-data-jpa` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-flyway` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-jackson` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-jdbc` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-logging` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-micrometer-metrics` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-security` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-tomcat` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-tomcat-runtime` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-validation` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-starter-webmvc` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-tomcat` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-transaction` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-validation` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-web-server` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.boot:spring-boot-webmvc` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.data:spring-data-commons` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.data:spring-data-jpa` | 4.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-api` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-apt` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-core` | 2.1.1 | Apache-2.0 | runtime |
| `org.springframework.modulith:spring-modulith-events-api` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-events-core` | 2.1.1 | Apache-2.0 | runtime |
| `org.springframework.modulith:spring-modulith-events-jackson` | 2.1.1 | Apache-2.0 | runtime |
| `org.springframework.modulith:spring-modulith-events-jdbc` | 2.1.1 | Apache-2.0 | runtime |
| `org.springframework.modulith:spring-modulith-moments` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-starter-core` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.modulith:spring-modulith-starter-jdbc` | 2.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-config` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-core` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-crypto` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-oauth2-core` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-oauth2-jose` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-oauth2-resource-server` | 7.1.1 | Apache-2.0 | compile |
| `org.springframework.security:spring-security-web` | 7.1.1 | Apache-2.0 | compile |
| `org.webjars:swagger-ui` | 5.32.11 | Apache-2.0 | compile |
| `org.webjars:webjars-locator-lite` | 1.1.4 | MIT | compile |
| `org.yaml:snakeyaml` | 2.6 | Apache-2.0 | compile |
| `tools.jackson.core:jackson-core` | 3.1.5 | Apache-2.0 | compile |
| `tools.jackson.core:jackson-databind` | 3.1.5 | Apache-2.0 | compile |

#### Только для тестов (test)

Используются при `./mvnw verify`, в образ не входят. Testcontainers при тестах запускает образ `postgres:17.6-alpine`.

| Артефакт (groupId:artifactId) | Версия | Лицензия | Область |
|---|---|---|---|
| `com.github.docker-java:docker-java-api` | 3.7.1 | Apache-2.0 | test |
| `com.github.docker-java:docker-java-transport` | 3.7.1 | Apache-2.0 | test |
| `com.github.docker-java:docker-java-transport-zerodep` | 3.7.1 | Apache-2.0 | test |
| `com.google.code.findbugs:jsr305` | 3.0.2 | Apache-2.0 | test |
| `com.jayway.jsonpath:json-path` | 2.10.0 | Apache-2.0 | test |
| `com.structurizr:structurizr-core` | 5.0.3 | Apache-2.0 | test |
| `com.structurizr:structurizr-export` | 5.0.3 | Apache-2.0 | test |
| `com.vaadin.external.google:android-json` | 0.0.20131108.vaadin1 | Apache-2.0 | test |
| `commons-codec:commons-codec` | 1.21.0 | Apache-2.0 | test |
| `commons-io:commons-io` | 2.20.0 | Apache-2.0 | test |
| `net.bytebuddy:byte-buddy-agent` | 1.18.11 | Apache-2.0 | test |
| `net.java.dev.jna:jna` | 5.18.1 | Apache-2.0 / LGPL-2.1+ (двойная) | test |
| `net.minidev:accessors-smart` | 2.6.0 | Apache-2.0 | test |
| `net.minidev:json-smart` | 2.6.0 | Apache-2.0 | test |
| `org.apache.commons:commons-compress` | 1.28.0 | Apache-2.0 | test |
| `org.apiguardian:apiguardian-api` | 1.1.2 | Apache-2.0 | test |
| `org.assertj:assertj-core` | 3.27.7 | Apache-2.0 | test |
| `org.awaitility:awaitility` | 4.3.0 | Apache-2.0 | test |
| `org.hamcrest:hamcrest` | 3.0 | BSD-3-Clause | test |
| `org.jetbrains:annotations` | 17.0.0 | Apache-2.0 | test |
| `org.junit.jupiter:junit-jupiter` | 6.0.3 | EPL-2.0 | test |
| `org.junit.jupiter:junit-jupiter-api` | 6.0.3 | EPL-2.0 | test |
| `org.junit.jupiter:junit-jupiter-engine` | 6.0.3 | EPL-2.0 | test |
| `org.junit.jupiter:junit-jupiter-params` | 6.0.3 | EPL-2.0 | test |
| `org.junit.platform:junit-platform-commons` | 6.0.3 | EPL-2.0 | test |
| `org.junit.platform:junit-platform-engine` | 6.0.3 | EPL-2.0 | test |
| `org.mockito:mockito-core` | 5.23.0 | MIT | test |
| `org.mockito:mockito-junit-jupiter` | 5.23.0 | MIT | test |
| `org.objenesis:objenesis` | 3.3 | Apache-2.0 | test |
| `org.opentest4j:opentest4j` | 1.3.0 | Apache-2.0 | test |
| `org.ow2.asm:asm` | 9.7.1 | BSD-3-Clause | test |
| `org.rnorth.duct-tape:duct-tape` | 1.0.8 | MIT | test |
| `org.skyscreamer:jsonassert` | 1.5.3 | Apache-2.0 | test |
| `org.springframework:spring-test` | 7.0.9 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-resttestclient` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-security-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-starter-jackson-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-starter-security-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-starter-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-starter-webmvc-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-test-autoconfigure` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-testcontainers` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.boot:spring-boot-webmvc-test` | 4.1.1 | Apache-2.0 | test |
| `org.springframework.modulith:spring-modulith-docs` | 2.1.1 | Apache-2.0 | test |
| `org.springframework.modulith:spring-modulith-starter-test` | 2.1.1 | Apache-2.0 | test |
| `org.springframework.modulith:spring-modulith-test` | 2.1.1 | Apache-2.0 | test |
| `org.springframework.security:spring-security-test` | 7.1.1 | Apache-2.0 | test |
| `org.testcontainers:testcontainers` | 2.0.5 | MIT | test |
| `org.testcontainers:testcontainers-database-commons` | 2.0.5 | MIT | test |
| `org.testcontainers:testcontainers-jdbc` | 2.0.5 | MIT | test |
| `org.testcontainers:testcontainers-junit-jupiter` | 2.0.5 | MIT | test |
| `org.testcontainers:testcontainers-postgresql` | 2.0.5 | MIT | test |
| `org.wiremock:wiremock-standalone` | 3.9.1 | Apache-2.0 | test |
| `org.xmlunit:xmlunit-core` | 2.11.0 | Apache-2.0 | test |

Лицензии — как указаны в POM-файлах; двойная лицензия — компонент доступен на условиях любой из них. EDL-1.0 (Eclipse Distribution License) — по тексту это BSD-3-Clause.

## Фронтенд

Список зависимостей — из `frontend/package-lock.json` (lockfile v3): версии и заявленные лицензии — поля `version` и `license` каждого пакета, production или dev — по флагу `dev`. Это перечень разрешённых npm-зависимостей, включая транзитивные: 275 записей установки, 275 уникальных пары «пакет + версия». Одинаковые пакет и версия, установленные в нескольких местах дерева, указаны один раз. Обновляется при изменении `frontend/package-lock.json`.

UI-компоненты кабинета собственные (CSS Modules), сторонняя дизайн-система не подключена. Интерфейс использует системные шрифты (`--font-family-system`); отдельные файлы шрифтов и внешние шрифтовые сервисы не подключены.

### Ключевые компоненты

| Компонент | Версия | Лицензия | Для чего |
|---|---|---|---|
| React, React DOM | 18.3.1 | MIT | интерфейс |
| React Router (`react-router-dom`) | 6.30.6 | MIT | маршрутизация |
| TanStack Query (`@tanstack/react-query`) | 5.104.5 | MIT | запросы к API, кэш и обновление данных |
| React Hook Form | 7.90.0 | MIT | формы анкеты |
| Hookform Resolvers (`@hookform/resolvers`) | 3.10.0 | MIT | связка форм с Zod |
| Zod | 3.25.76 | MIT | проверка полей анкеты |
| Nginx (`nginx:stable-alpine`) | тег `stable`, точная версия не закреплена | BSD-2-Clause | отдаёт собранный кабинет и проксирует `/api/v1` на бэкенд |

### Инструменты сборки и тестирования (в финальный образ не устанавливаются)

| Компонент | Версия | Лицензия | Для чего |
|---|---|---|---|
| Node.js (`node:22-alpine`) | 22, точная версия не закреплена | MIT; встроенные сторонние компоненты — под своими лицензиями | сборка в `frontend/Dockerfile` |
| TypeScript | 5.9.3 | Apache-2.0 | язык, проверка типов |
| Vite | 7.3.7 | MIT | сборка и dev-сервер |
| `@vitejs/plugin-react` | 4.7.0 | MIT | поддержка React в Vite |
| Vitest | 3.2.7 | MIT | модульные тесты |
| Playwright (`@playwright/test`) | 1.64.1 | Apache-2.0 | сквозные тесты в браузере |
| axe-core, `@axe-core/playwright` | 4.14.0 | MPL-2.0 | проверки доступности в тестах |
| ESLint, `@eslint/js` | 9.39.5 | MIT | статический анализ |
| typescript-eslint | 8.72.0 | MIT | правила ESLint для TypeScript |
| `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh` | 5.2.0, 0.4.26 | MIT | правила ESLint для React |

### Образы Docker

| Образ | Лицензия | Для чего |
|---|---|---|
| `node:22-alpine` (стадия сборки) | Node.js — MIT; встроенные сторонние компоненты и пакеты Alpine Linux — под своими лицензиями | `npm ci` и `npm run build` в `frontend/Dockerfile` |
| `nginx:stable-alpine` (образ кабинета) | Nginx — BSD-2-Clause; Alpine Linux — пакеты под своими лицензиями | статические файлы кабинета и прокси к API |

Источники лицензий среды сборки и сервера: [Node.js 22 LICENSE](https://github.com/nodejs/node/blob/v22.0.0/LICENSE), [Nginx LICENSE](https://nginx.org/LICENSE). Эти таблицы не перечисляют каждый системный пакет Docker-образов. Финальная стадия копирует из сборочной только `dist/`: Node.js и каталог `node_modules` в образ кабинета не переносятся.

### Все зависимости

#### Production (16 пакетов)

Пакеты без флага `dev` в lock-файле. Vite включает в итоговые JavaScript и CSS используемый код; наличие пакета в этом списке само по себе не означает, что его код попал в кабинет. `peer` — в дереве есть установка этой версии как peer-зависимости другого пакета.

| Пакет | Версия | Лицензия | Примечание |
|---|---|---|---|
| `@hookform/resolvers` | 3.10.0 | MIT |  |
| `@remix-run/router` | 1.23.4 | MIT |  |
| `@tanstack/query-core` | 5.104.5 | MIT |  |
| `@tanstack/react-query` | 5.104.5 | MIT |  |
| `@types/prop-types` | 15.7.15 | MIT |  |
| `@types/react` | 18.3.31 | MIT | peer |
| `csstype` | 3.2.3 | MIT |  |
| `js-tokens` | 4.0.0 | MIT |  |
| `loose-envify` | 1.4.0 | MIT |  |
| `react` | 18.3.1 | MIT | peer |
| `react-dom` | 18.3.1 | MIT | peer |
| `react-hook-form` | 7.90.0 | MIT | peer |
| `react-router` | 6.30.6 | MIT |  |
| `react-router-dom` | 6.30.6 | MIT |  |
| `scheduler` | 0.23.2 | MIT |  |
| `zod` | 3.25.76 | MIT |  |

#### Dev — сборка, тесты, анализ (259 пакетов)

Пакеты с флагом `dev`: инструменты сборки, тестирования и анализа; их каталог `node_modules` в финальный образ не копируется. `optional` — необязательные зависимости; для платформенных двоичных пакетов установка зависит от ОС и архитектуры.

| Пакет | Версия | Лицензия | Примечание |
|---|---|---|---|
| `@axe-core/playwright` | 4.14.0 | MPL-2.0 |  |
| `@babel/code-frame` | 7.29.7 | MIT |  |
| `@babel/compat-data` | 7.29.7 | MIT |  |
| `@babel/core` | 7.29.7 | MIT | peer |
| `@babel/generator` | 7.29.8 | MIT |  |
| `@babel/helper-compilation-targets` | 7.29.7 | MIT |  |
| `@babel/helper-globals` | 7.29.7 | MIT |  |
| `@babel/helper-module-imports` | 7.29.7 | MIT |  |
| `@babel/helper-module-transforms` | 7.29.7 | MIT |  |
| `@babel/helper-plugin-utils` | 7.29.7 | MIT |  |
| `@babel/helpers` | 7.29.10 | MIT |  |
| `@babel/helper-string-parser` | 7.29.7 | MIT |  |
| `@babel/helper-validator-identifier` | 7.29.7 | MIT |  |
| `@babel/helper-validator-option` | 7.29.7 | MIT |  |
| `@babel/parser` | 7.29.9 | MIT |  |
| `@babel/plugin-transform-react-jsx-self` | 7.29.7 | MIT |  |
| `@babel/plugin-transform-react-jsx-source` | 7.29.7 | MIT |  |
| `@babel/template` | 7.29.7 | MIT |  |
| `@babel/traverse` | 7.29.10 | MIT |  |
| `@babel/types` | 7.29.8 | MIT |  |
| `@esbuild/aix-ppc64` | 0.28.2 | MIT | optional |
| `@esbuild/android-arm` | 0.28.2 | MIT | optional |
| `@esbuild/android-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/android-x64` | 0.28.2 | MIT | optional |
| `@esbuild/darwin-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/darwin-x64` | 0.28.2 | MIT | optional |
| `@esbuild/freebsd-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/freebsd-x64` | 0.28.2 | MIT | optional |
| `@esbuild/linux-arm` | 0.28.2 | MIT | optional |
| `@esbuild/linux-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/linux-ia32` | 0.28.2 | MIT | optional |
| `@esbuild/linux-loong64` | 0.28.2 | MIT | optional |
| `@esbuild/linux-mips64el` | 0.28.2 | MIT | optional |
| `@esbuild/linux-ppc64` | 0.28.2 | MIT | optional |
| `@esbuild/linux-riscv64` | 0.28.2 | MIT | optional |
| `@esbuild/linux-s390x` | 0.28.2 | MIT | optional |
| `@esbuild/linux-x64` | 0.28.2 | MIT | optional |
| `@esbuild/netbsd-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/netbsd-x64` | 0.28.2 | MIT | optional |
| `@esbuild/openbsd-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/openbsd-x64` | 0.28.2 | MIT | optional |
| `@esbuild/openharmony-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/sunos-x64` | 0.28.2 | MIT | optional |
| `@esbuild/win32-arm64` | 0.28.2 | MIT | optional |
| `@esbuild/win32-ia32` | 0.28.2 | MIT | optional |
| `@esbuild/win32-x64` | 0.28.2 | MIT | optional |
| `@eslint/config-array` | 0.21.2 | Apache-2.0 |  |
| `@eslint/config-helpers` | 0.4.2 | Apache-2.0 |  |
| `@eslint/core` | 0.17.0 | Apache-2.0 |  |
| `@eslint/eslintrc` | 3.3.7 | MIT |  |
| `@eslint/js` | 9.39.5 | MIT |  |
| `@eslint/object-schema` | 2.1.7 | Apache-2.0 |  |
| `@eslint/plugin-kit` | 0.4.1 | Apache-2.0 |  |
| `@eslint-community/eslint-utils` | 4.10.1 | MIT |  |
| `@eslint-community/regexpp` | 4.12.2 | MIT |  |
| `@humanfs/core` | 0.19.2 | Apache-2.0 |  |
| `@humanfs/node` | 0.16.8 | Apache-2.0 |  |
| `@humanfs/types` | 0.15.0 | Apache-2.0 |  |
| `@humanwhocodes/module-importer` | 1.0.1 | Apache-2.0 |  |
| `@humanwhocodes/retry` | 0.4.3 | Apache-2.0 |  |
| `@jridgewell/gen-mapping` | 0.3.13 | MIT |  |
| `@jridgewell/remapping` | 2.3.5 | MIT |  |
| `@jridgewell/resolve-uri` | 3.1.2 | MIT |  |
| `@jridgewell/sourcemap-codec` | 1.6.0 | MIT |  |
| `@jridgewell/trace-mapping` | 0.3.31 | MIT |  |
| `@napi-rs/lzma-linux-x64-gnu` | 1.5.1 | MIT | optional |
| `@playwright/test` | 1.64.1 | Apache-2.0 |  |
| `@rolldown/pluginutils` | 1.0.0-beta.27 | MIT |  |
| `@rollup/rollup-android-arm64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-android-arm-eabi` | 4.64.12 | MIT | optional |
| `@rollup/rollup-darwin-arm64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-darwin-x64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-freebsd-arm64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-freebsd-x64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-arm64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-arm64-musl` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-arm-gnueabihf` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-arm-musleabihf` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-loong64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-loong64-musl` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-ppc64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-ppc64-musl` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-riscv64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-riscv64-musl` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-s390x-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-x64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-linux-x64-musl` | 4.64.12 | MIT | optional |
| `@rollup/rollup-openbsd-x64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-openharmony-arm64` | 4.64.12 | MIT | optional |
| `@rollup/rollup-win32-arm64-msvc` | 4.64.12 | MIT | optional |
| `@rollup/rollup-win32-ia32-msvc` | 4.64.12 | MIT | optional |
| `@rollup/rollup-win32-x64-gnu` | 4.64.12 | MIT | optional |
| `@rollup/rollup-win32-x64-msvc` | 4.64.12 | MIT | optional |
| `@types/babel__core` | 7.20.5 | MIT |  |
| `@types/babel__generator` | 7.27.0 | MIT |  |
| `@types/babel__template` | 7.4.4 | MIT |  |
| `@types/babel__traverse` | 7.28.0 | MIT |  |
| `@types/chai` | 5.2.3 | MIT |  |
| `@types/deep-eql` | 4.0.2 | MIT |  |
| `@types/estree` | 1.0.9 | MIT |  |
| `@types/json-schema` | 7.0.15 | MIT |  |
| `@types/ms` | 2.1.0 | MIT | optional |
| `@types/node` | 22.20.5 | MIT | peer |
| `@types/react-dom` | 18.3.7 | MIT |  |
| `@typescript-eslint/eslint-plugin` | 8.72.0 | MIT |  |
| `@typescript-eslint/parser` | 8.72.0 | MIT | peer |
| `@typescript-eslint/project-service` | 8.72.0 | MIT |  |
| `@typescript-eslint/scope-manager` | 8.72.0 | MIT |  |
| `@typescript-eslint/tsconfig-utils` | 8.72.0 | MIT |  |
| `@typescript-eslint/types` | 8.72.0 | MIT |  |
| `@typescript-eslint/typescript-estree` | 8.72.0 | MIT |  |
| `@typescript-eslint/type-utils` | 8.72.0 | MIT |  |
| `@typescript-eslint/utils` | 8.72.0 | MIT |  |
| `@typescript-eslint/visitor-keys` | 8.72.0 | MIT |  |
| `@vitejs/plugin-react` | 4.7.0 | MIT |  |
| `@vitest/expect` | 3.2.7 | MIT |  |
| `@vitest/mocker` | 3.2.7 | MIT |  |
| `@vitest/pretty-format` | 3.2.7 | MIT |  |
| `@vitest/runner` | 3.2.7 | MIT |  |
| `@vitest/snapshot` | 3.2.7 | MIT |  |
| `@vitest/spy` | 3.2.7 | MIT |  |
| `@vitest/utils` | 3.2.7 | MIT |  |
| `acorn` | 8.19.0 | MIT | peer |
| `acorn-jsx` | 5.3.2 | MIT |  |
| `ajv` | 6.15.0 | MIT |  |
| `ansi-styles` | 4.3.0 | MIT |  |
| `argparse` | 2.0.1 | Python-2.0 |  |
| `assertion-error` | 2.0.1 | MIT |  |
| `axe-core` | 4.14.0 | MPL-2.0 |  |
| `balanced-match` | 1.0.2 | MIT |  |
| `balanced-match` | 4.0.4 | MIT |  |
| `baseline-browser-mapping` | 2.11.30 | Apache-2.0 |  |
| `brace-expansion` | 1.1.21 | MIT |  |
| `brace-expansion` | 5.0.12 | MIT |  |
| `browserslist` | 4.29.4 | MIT | peer |
| `cac` | 6.7.14 | MIT |  |
| `callsites` | 3.1.0 | MIT |  |
| `caniuse-lite` | 1.0.30001818 | CC-BY-4.0 |  |
| `chai` | 5.3.3 | MIT |  |
| `chalk` | 4.1.2 | MIT |  |
| `check-error` | 2.1.3 | MIT |  |
| `color-convert` | 2.0.1 | MIT |  |
| `color-name` | 1.1.4 | MIT |  |
| `concat-map` | 0.0.1 | MIT |  |
| `convert-source-map` | 2.0.0 | MIT |  |
| `cross-spawn` | 7.0.6 | MIT |  |
| `debug` | 4.4.3 | MIT |  |
| `deep-eql` | 5.0.2 | MIT |  |
| `deep-is` | 0.1.4 | MIT |  |
| `electron-to-chromium` | 1.5.463 | ISC |  |
| `esbuild` | 0.28.2 | MIT |  |
| `escalade` | 3.2.0 | MIT |  |
| `escape-string-regexp` | 4.0.0 | MIT |  |
| `eslint` | 9.39.5 | MIT | peer |
| `eslint-plugin-react-hooks` | 5.2.0 | MIT |  |
| `eslint-plugin-react-refresh` | 0.4.26 | MIT |  |
| `eslint-scope` | 8.4.0 | BSD-2-Clause |  |
| `eslint-visitor-keys` | 3.4.3 | Apache-2.0 |  |
| `eslint-visitor-keys` | 4.2.1 | Apache-2.0 |  |
| `eslint-visitor-keys` | 5.0.1 | Apache-2.0 |  |
| `es-module-lexer` | 1.7.0 | MIT |  |
| `espree` | 10.4.0 | BSD-2-Clause |  |
| `esquery` | 1.7.0 | BSD-3-Clause |  |
| `esrecurse` | 4.3.0 | BSD-2-Clause |  |
| `estraverse` | 5.3.0 | BSD-2-Clause |  |
| `estree-walker` | 3.0.3 | MIT |  |
| `esutils` | 2.0.3 | BSD-2-Clause |  |
| `expect-type` | 1.4.0 | Apache-2.0 |  |
| `fast-deep-equal` | 3.1.3 | MIT |  |
| `fast-json-stable-stringify` | 2.1.0 | MIT |  |
| `fast-levenshtein` | 2.0.6 | MIT |  |
| `fdir` | 6.5.0 | MIT |  |
| `file-entry-cache` | 8.0.0 | MIT |  |
| `find-up` | 5.0.0 | MIT |  |
| `flat-cache` | 4.0.1 | MIT |  |
| `flatted` | 3.4.4 | ISC |  |
| `fsevents` | 2.3.3 | MIT | optional |
| `gensync` | 1.0.0-beta.2 | MIT |  |
| `globals` | 14.0.0 | MIT |  |
| `globals` | 16.5.0 | MIT |  |
| `glob-parent` | 6.0.2 | ISC |  |
| `has-flag` | 4.0.0 | MIT |  |
| `ignore` | 5.3.2 | MIT |  |
| `ignore` | 7.0.13 | MIT |  |
| `import-fresh` | 3.3.1 | MIT |  |
| `imurmurhash` | 0.1.4 | MIT |  |
| `isexe` | 2.0.0 | ISC |  |
| `is-extglob` | 2.1.1 | MIT |  |
| `is-glob` | 4.0.3 | MIT |  |
| `jsesc` | 3.1.0 | MIT |  |
| `json5` | 2.2.3 | MIT |  |
| `json-buffer` | 3.0.1 | MIT |  |
| `json-schema-traverse` | 0.4.1 | MIT |  |
| `json-stable-stringify-without-jsonify` | 1.0.1 | MIT |  |
| `js-tokens` | 9.0.1 | MIT |  |
| `js-yaml` | 4.3.2 | MIT |  |
| `keyv` | 4.5.4 | MIT |  |
| `levn` | 0.4.1 | MIT |  |
| `locate-path` | 6.0.0 | MIT |  |
| `lodash.merge` | 4.6.2 | MIT |  |
| `loupe` | 3.2.1 | MIT |  |
| `lru-cache` | 5.1.1 | ISC |  |
| `magic-string` | 0.30.21 | MIT |  |
| `minimatch` | 3.1.5 | ISC |  |
| `minimatch` | 10.2.6 | BlueOak-1.0.0 |  |
| `ms` | 2.1.3 | MIT |  |
| `nanoid` | 3.3.20 | MIT |  |
| `natural-compare` | 1.4.0 | MIT |  |
| `node-releases` | 2.0.59 | MIT |  |
| `optionator` | 0.9.4 | MIT |  |
| `parent-module` | 1.0.1 | MIT |  |
| `pathe` | 2.0.3 | MIT |  |
| `path-exists` | 4.0.0 | MIT |  |
| `path-key` | 3.1.1 | MIT |  |
| `pathval` | 2.0.1 | MIT |  |
| `picocolors` | 1.1.1 | ISC |  |
| `picomatch` | 4.0.7 | MIT | peer |
| `playwright` | 1.64.1 | Apache-2.0 |  |
| `playwright-core` | 1.64.1 | Apache-2.0 | peer |
| `p-limit` | 3.1.0 | MIT |  |
| `p-locate` | 5.0.0 | MIT |  |
| `postcss` | 8.5.31 | MIT |  |
| `prelude-ls` | 1.2.1 | MIT |  |
| `punycode` | 2.3.1 | MIT |  |
| `react-refresh` | 0.17.0 | MIT |  |
| `resolve-from` | 4.0.0 | MIT |  |
| `rollup` | 4.64.12 | MIT |  |
| `semver` | 6.3.1 | ISC |  |
| `semver` | 7.8.5 | ISC |  |
| `shebang-command` | 2.0.0 | MIT |  |
| `shebang-regex` | 3.0.0 | MIT |  |
| `siginfo` | 2.0.0 | ISC |  |
| `source-map-js` | 1.2.2 | BSD-3-Clause |  |
| `stackback` | 0.0.2 | MIT |  |
| `std-env` | 3.10.0 | MIT |  |
| `strip-json-comments` | 3.1.1 | MIT |  |
| `strip-literal` | 3.1.0 | MIT |  |
| `supports-color` | 7.2.0 | MIT |  |
| `tinybench` | 2.9.0 | MIT |  |
| `tinyexec` | 0.3.2 | MIT |  |
| `tinyglobby` | 0.2.17 | MIT |  |
| `tinypool` | 1.1.1 | MIT |  |
| `tinyrainbow` | 2.0.0 | MIT |  |
| `tinyspy` | 4.0.6 | MIT |  |
| `ts-api-utils` | 2.5.0 | MIT |  |
| `type-check` | 0.4.0 | MIT |  |
| `typescript` | 5.9.3 | Apache-2.0 | peer |
| `typescript-eslint` | 8.72.0 | MIT |  |
| `undici-types` | 6.21.0 | MIT |  |
| `update-browserslist-db` | 1.3.4 | MIT |  |
| `uri-js` | 4.4.1 | BSD-2-Clause |  |
| `vite` | 7.3.7 | MIT | peer |
| `vite-node` | 3.2.4 | MIT |  |
| `vitest` | 3.2.7 | MIT |  |
| `which` | 2.0.2 | ISC |  |
| `why-is-node-running` | 2.3.0 | MIT |  |
| `word-wrap` | 1.2.5 | MIT |  |
| `yallist` | 3.1.1 | ISC |  |
| `yocto-queue` | 0.1.0 | MIT |  |
