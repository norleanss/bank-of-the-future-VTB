package ru.creditconveyor;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.github.tomakehurst.wiremock.WireMockServer;

import ru.creditconveyor.identity.AccessTokens;

/**
 * Один контекст и одна база на все классы: на пустые таблицы не рассчитываем. Фоновая задача истечения
 * сама не срабатывает (интервал — сутки), её вызывают явно. Внешние источники — WireMock с теми же моками,
 * что в docker-compose; таймауты и паузы между повторами короче, чтобы тесты сбоев шли быстро.
 */
@SpringBootTest(properties = {
        "app.offer.expiry-check-interval=PT24H",
        "integrations.read-timeout=500ms",
        "integrations.retry-delay=50ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Sql(scripts = "/sql/other-client.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
public abstract class IntegrationTest {

    /** Демо-клиент Иван Петров — шесть демо-компаний */
    protected static final String CLIENT = "client";
    protected static final String UNDERWRITER = "underwriter";
    /** Второй клиент из тестовой фикстуры со своей «чужой» компанией */
    protected static final String OTHER_CLIENT = "other-client";

    /** «Одобрение» */
    protected static final String SEVERNY_VETER = "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90";
    /** «Ручная проверка» */
    protected static final String MOROZOV = "118ffe67-739c-4053-94df-9024ddfa44a5";
    /** «Отказ: просрочка» */
    protected static final String ALTAIR = "bd88f8b9-b915-49ff-ab95-85658a48d64b";
    /** «Предварительное одобрение» */
    protected static final String TECHNOPROM = "21a1be18-cb82-4fed-b463-8f7f5059fb6e";
    /** «Гамма-Старт» — бизнесу 3 месяца */
    protected static final String SOKOLOVA = "9b6d2f4a-8c1e-4a7b-b3d5-2e9f0a1c7d64";
    /** «Отказ: ликвидация» — статус LIQUIDATING */
    protected static final String VEKTOR_STROY = "c4e8a1b7-2d9f-4f3c-8a6e-5b0d7c2f9e13";
    /** Компания другого клиента */
    protected static final String FOREIGN_COMPANY = "183a6eb2-3b32-4f2b-a9c9-27a6aa5d3665";

    /** Сценарии сбоев добавляют свои заглушки; после теста остаются только моки из репозитория. */
    protected static final WireMockServer SOURCES = startSources();

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected TestClock clock;

    @Autowired
    private AccessTokens accessTokens;

    @AfterEach
    void resetClockAndSources() {
        clock.set(TestcontainersConfiguration.TODAY);
        SOURCES.resetAll();
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        registry.add("integrations.egrul.base-url", () -> SOURCES.baseUrl() + "/egrul");
        registry.add("integrations.fns.base-url", () -> SOURCES.baseUrl() + "/fns");
        registry.add("integrations.bki.base-url", () -> SOURCES.baseUrl() + "/bki");
    }

    private static WireMockServer startSources() {
        WireMockServer server = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("../mocks/wiremock"));
        server.start();
        return server;
    }

    /** Токен выпускается по текущим тестовым часам. */
    protected String bearer(String login) {
        return "Bearer " + accessTokens.issueFor(login).accessToken();
    }
}
