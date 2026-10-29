package ru.creditconveyor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;

/**
 * Сквозная обработка заявки, как её проходит эксперт: отправка → обогащение через моки WireMock → скоринг и политика →
 * предложение → подписание → выдача. Шесть демо-компаний дают свои сценарии (таблица в docs/scoring.md).
 */
class ConveyorIntegrationTests extends IntegrationTest {

    private static final Set<String> IN_PROGRESS = Set.of("SUBMITTED", "ENRICHMENT", "SCORING");

    /** Выручка 20 млн, расходы 14 млн, платежи 50 тыс. в месяц: рентабельность 30%, нагрузка 3%. */
    private static final String SEVERNY_VETER_LOAN = """
            {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "%s",
             "term": %d, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM", "repaymentType": "ANNUITY",
             "financials": {"annualRevenue": "20000000.00", "annualExpenses": "14000000.00",
                            "monthlyDebtPayment": "50000.00"}}
            """;

    @Autowired
    JdbcTemplate jdbc;

    // --- Демо-компании ---

    /** «Одобрение»: балл 100, лимит 20 млн × 0,5 − 0,6 млн долга; 500 тыс. ≤ N — автоодобрение, затем подписание. */
    @Test
    void severnyVeterIsApprovedSignsAndGetsMoney() {
        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("APPROVED");
        card.extractingPath("$.reasons").asArray().isEmpty();
        card.extractingPath("$.offer.amount").isEqualTo("500000.00");
        card.extractingPath("$.offer.rate").isEqualTo(23.5);
        card.extractingPath("$.offer.preliminary").isEqualTo(false);
        card.extractingPath("$.offer.monthlyPayment").isNotNull();
        card.extractingPath("$.availableActions").asArray().containsExactly("ACCEPT_OFFER", "DECLINE_OFFER");

        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
        var code = assertThat(post(CLIENT, "/applications/" + id + "/signing/request-code", null)).hasStatusOk().bodyJson();
        // Код живёт 5 минут; время — в поясе приложения
        code.extractingPath("$.expiresAt").asString().startsWith("2026-11-01T12:05").endsWith("+03:00");
        String demoCode = read(post(CLIENT, "/applications/" + id + "/signing/request-code", null), "$.demoCode");

        assertThat(confirm(id, otherThan(demoCode))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].message").isEqualTo("Неверный код. Осталось попыток: 2");
        assertThat(confirm(id, demoCode)).hasStatusOk().bodyJson().extractingPath("$.status").isIn("SIGNED", "DISBURSED");

        var disbursed = awaitStatus(CLIENT, id, "DISBURSED");
        disbursed.extractingPath("$.nextStepHint").isEqualTo("Деньги перечислены на счёт компании");
        var history = assertThat(get(CLIENT, "/applications/" + id + "/history")).hasStatusOk().bodyJson();
        history.extractingPath("$[*].toStatus").asArray().containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT",
                "SCORING", "APPROVED", "OFFER_ACCEPTED", "SIGNED", "DISBURSED");
        history.extractingPath("$[*].actorType").asArray().containsExactly("CLIENT", "CLIENT", "SYSTEM",
                "SYSTEM", "SYSTEM", "CLIENT", "CLIENT", "SYSTEM");
        history.extractingPath("$[4].comment").isEqualTo("Балл 100, грейд A. Автоматическое одобрение");

        // Балл с разбивкой, решение и ответы источников сохранены; все события доставлены
        assertThat(jdbc.queryForMap("SELECT score, grade FROM scoring.scoring_results WHERE application_id = ?::uuid", id))
                .containsEntry("score", 100).containsEntry("grade", "A");
        assertThat(jdbc.queryForObject("""
                SELECT sum(f.points) FROM scoring.scoring_factors f
                JOIN scoring.scoring_results r ON r.id = f.scoring_result_id WHERE r.application_id = ?::uuid""",
                Integer.class, id)).isEqualTo(100);
        assertThat(jdbc.queryForMap("SELECT outcome, limit_amount FROM scoring.decisions WHERE application_id = ?::uuid", id))
                .containsEntry("outcome", "APPROVE").containsEntry("limit_amount", new BigDecimal("9400000.00"));
        assertThat(jdbc.queryForList("""
                SELECT s.source || ':' || s.status FROM enrichment.source_results s
                JOIN enrichment.enrichments e ON e.id = s.enrichment_id WHERE e.application_id = ?::uuid
                ORDER BY s.source""", String.class, id)).containsExactly("BKI:OK", "EGRUL:OK", "FNS:OK");
        // Все события заявки доставлены; доставленные удаляются из журнала
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM events.event_publication WHERE serialized_event LIKE '%' || ? || '%'",
                Integer.class, id)).isZero());
    }

    /** «Ручная проверка»: просрочка 45 дней — мягкий фактор, балл 75 сам по себе прошёл бы. */
    @Test
    void morozovGoesToManualReview() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "CREDIT_LINE", "purpose": "WORKING_CAPITAL", "requestedAmount": "1000000.00",
                 "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES",
                 "financials": {"annualRevenue": "12000000.00", "annualExpenses": "9000000.00", "monthlyDebtPayment": "50000.00"}}
                """.formatted(MOROZOV));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("OVERDUE_31_60");
        card.extractingPath("$.availableActions").asArray().containsExactly("CANCEL");
        assertThat(score(id)).isEqualTo(75);
    }

    @Test
    void altairIsRejectedForOverdue() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "CREDIT_LINE", "purpose": "WORKING_CAPITAL", "requestedAmount": "3000000.00",
                 "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES",
                 "financials": {"annualRevenue": "30000000.00", "annualExpenses": "25000000.00", "monthlyDebtPayment": "300000.00"}}
                """.formatted(ALTAIR));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("REJECTED");
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("OVERDUE_GT_60");
        card.extractingPath("$.reasons[0].message").isEqualTo("Есть просрочка по кредиту больше 60 дней");
        card.extractingPath("$.reasons[0].advice").isEqualTo("Погасите просрочку и подайте заявку снова через 3 месяца");
        // Стоп-фактор — до скоринга: балл не считался
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scoring.scoring_results WHERE application_id = ?::uuid",
                Integer.class, id)).isZero();
    }

    /** «Предварительное одобрение»: 5 млн > N; после согласия клиента условия подтверждает андеррайтер. */
    @Test
    void technopromGetsPreliminaryApproval() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "5000000.00",
                 "term": 36, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM", "repaymentType": "ANNUITY",
                 "financials": {"annualRevenue": "60000000.00", "annualExpenses": "48000000.00", "monthlyDebtPayment": "200000.00"}}
                """.formatted(TECHNOPROM));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("APPROVED");
        card.extractingPath("$.offer.preliminary").isEqualTo(true);
        card.extractingPath("$.offer.monthlyPayment").isEqualTo("194854.03");
        card.extractingPath("$.nextStepHint").isEqualTo("Предварительно одобрено 5 000 000 ₽ на 36 месяцев. "
                + "Примите предложение до 08.11.2026 — после этого условия подтвердит специалист банка");
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("PENDING_CONFIRMATION");
    }

    /** «Гамма-Старт»: бизнесу 3 месяца, анкета без финансов — балл нормируется (45 из 55 → 82). */
    @Test
    void sokolovaGetsGammaStartWithoutFinancials() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "GAMMA_START", "purpose": "STARTUP", "requestedAmount": "500000.00",
                 "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES"}
                """.formatted(SOKOLOVA));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("APPROVED");
        card.extractingPath("$.offer.rate").isEqualTo(0.3);
        card.extractingPath("$.offer.rateUnit").isEqualTo("PERCENT_PER_DAY");
        card.extractingPath("$.offer.monthlyPayment").isEqualTo("45000.00");
        card.extractingPath("$.offer.agreementTermMonths").isEqualTo(18);
        assertThat(score(id)).isEqualTo(82);
    }

    /** Кредит одной суммой — бизнесу от 12 месяцев: молодой компании отказ ещё при отправке, по данным нашей базы. */
    @Test
    void sokolovaCannotSubmitLoanForEstablishedBusiness() {
        String id = read(post(CLIENT, "/applications", """
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "800000.00",
                 "term": 12, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM",
                 "financials": {"annualRevenue": "3000000.00", "annualExpenses": "2000000.00", "monthlyDebtPayment": "0.00"}}
                """.formatted(SOKOLOVA)), "$.id");

        var problem = assertThat(post(CLIENT, "/applications/" + id + "/submit", null))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
        problem.extractingPath("$.errors[0].field").isEqualTo("productCode");
        problem.extractingPath("$.errors[0].message")
                .isEqualTo("Продукт «Кредит одной суммой» доступен бизнесу от 12 месяцев, компании — 3 месяца");
        assertThat(status(CLIENT, id)).isEqualTo("DRAFT");
    }

    @Test
    void vektorStroyIsRejectedForLiquidation() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "500000.00",
                 "term": 30, "termUnit": "DAYS", "fundingMode": "ON_DEMAND"}
                """.formatted(VEKTOR_STROY));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("REJECTED");
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("COMPANY_LIQUIDATING");
    }

    /** Предварительный платёж подбора считается так же, как в предложении: при тех же сумме, сроке и подписке — равен. */
    @Test
    void matchEstimateEqualsOfferPayment() {
        String base = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));
        String withSubscription = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24)
                .replaceFirst("\\{", "{\"subscription\": true, "));
        awaitStatus(CLIENT, base, "APPROVED").extractingPath("$.offer.subscription").isEqualTo(false);
        awaitStatus(CLIENT, withSubscription, "APPROVED").extractingPath("$.offer.rate").isEqualTo(14.0);
        String offerPayment = read(get(CLIENT, "/applications/" + base), "$.offer.monthlyPayment");
        String offerPaymentWithSubscription = read(get(CLIENT, "/applications/" + withSubscription),
                "$.offer.monthlyPayment");

        var estimate = assertThat(post(CLIENT, "/products/match", """
                {"companyId": "%s", "purpose": "EQUIPMENT", "requestedAmount": "500000.00", "term": 24,
                 "termUnit": "MONTHS", "fundingMode": "LUMP_SUM"}""".formatted(SEVERNY_VETER))).hasStatusOk().bodyJson();
        String loan = "$.items[?(@.product.code == 'LUMP_SUM_LOAN')].estimate";
        estimate.extractingPath(loan + ".monthlyPayment").asArray().containsExactly(offerPayment);
        estimate.extractingPath(loan + ".monthlyPaymentWithSubscription").asArray()
                .containsExactly(offerPaymentWithSubscription);
        estimate.extractingPath(loan + ".repaymentType").asArray().containsExactly("ANNUITY");
        // Неподходящим продуктам расчёт не нужен
        estimate.extractingPath("$.items[?(@.product.code == 'GAMMA_START')].estimate").asArray().isEmpty();
    }

    // --- Контрпредложение и запрос сведений ---

    /** Лимит 9,4 млн меньше запрошенных 12 млн: предлагаем лимит; это больше N — предложение предварительное. */
    @Test
    void requestAboveLimitGetsCounterOffer() {
        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "12000000.00", 36));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("COUNTER_OFFER");
        card.extractingPath("$.offer.amount").isEqualTo("9400000.00");
        card.extractingPath("$.offer.term").isEqualTo(36);
        card.extractingPath("$.offer.preliminary").isEqualTo(true);
        card.extractingPath("$.reasons[0].code").isEqualTo("AMOUNT_ABOVE_LIMIT");
        card.extractingPath("$.reasons[0].message")
                .isEqualTo("Запрошенная сумма больше доступного лимита. Можем предложить 9 400 000 ₽");
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("PENDING_CONFIRMATION");
    }

    /** Анкета без финансов → запрос сведений; клиент дозаполняет и отвечает — заявка обрабатывается заново. */
    @Test
    void needInfoIsAnsweredAndApplicationIsProcessedAgain() {
        String id = submit(CLIENT, """
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "500000.00",
                 "term": 24, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM"}
                """.formatted(SEVERNY_VETER));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("NEED_INFO");
        card.extractingPath("$.availableActions").asArray().containsExactly("RESPOND_INFO", "UPLOAD_DOCUMENT", "CANCEL");
        card.extractingPath("$.openInfoRequest.requestedFields").asArray().containsExactly(
                "financials.annualRevenue", "financials.annualExpenses", "financials.monthlyDebtPayment");
        card.extractingPath("$.openInfoRequest.message")
                .isEqualTo("Укажите в анкете: выручка за год, расходы за год, ежемесячные платежи по кредитам");
        card.extractingPath("$.reasons[0].code").isEqualTo("MISSING_FIELDS");
        String requestId = read(get(CLIENT, "/applications/" + id), "$.openInfoRequest.id");

        // Ответ без нужных полей и ответ не на тот запрос
        assertThat(respond(id, requestId)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[*].field").asArray().containsExactly(
                        "financials.annualRevenue", "financials.annualExpenses", "financials.monthlyDebtPayment");
        assertThat(respond(id, "9b2c1d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e")).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(mvc.patch().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"financials": {"annualRevenue": "20000000.00", "annualExpenses": "14000000.00",
                                        "monthlyDebtPayment": "50000.00"}}""")).hasStatusOk();
        var answered = assertThat(respond(id, requestId)).hasStatusOk().bodyJson();
        answered.extractingPath("$.status").isIn("ENRICHMENT", "SCORING", "APPROVED");
        answered.doesNotHavePath("$.openInfoRequest");

        awaitDecision(CLIENT, id).extractingPath("$.status").isEqualTo("APPROVED");
        var history = assertThat(get(CLIENT, "/applications/" + id + "/history")).bodyJson();
        history.extractingPath("$[*].toStatus").asArray().containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT",
                "SCORING", "NEED_INFO", "ENRICHMENT", "SCORING", "APPROVED");
        history.extractingPath("$[5].actorType").isEqualTo("CLIENT");
        history.extractingPath("$[5].comment").isEqualTo("Добавил выручку и расходы");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrichment.enrichments WHERE application_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);
    }

    // --- Сбои источников ---

    /** БКИ отвечает 500: запрос повторяется дважды, потом заявка уходит на ручную проверку без скоринга. */
    @Test
    void unavailableSourceSendsApplicationToManualReview() {
        SOURCES.stubFor(WireMock.get(urlPathEqualTo("/bki/companies/7841256395/credit-report")).atPriority(1)
                .willReturn(serverError()));

        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));

        var card = awaitDecision(CLIENT, id);
        card.extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("SOURCE_UNAVAILABLE");
        SOURCES.verify(3, getRequestedFor(urlPathEqualTo("/bki/companies/7841256395/credit-report")));
        assertThat(sourceResult(id, "BKI")).containsEntry("status", "ERROR").containsEntry("attempts", 3)
                .containsEntry("http_status", 500);
        assertThat(get(CLIENT, "/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[*].toStatus").asArray()
                .containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT", "MANUAL_REVIEW");
    }

    @Test
    void slowSourceIsTimeout() {
        SOURCES.stubFor(WireMock.get(urlPathEqualTo("/fns/companies/7841256395/tax-debts")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));

        awaitDecision(CLIENT, id).extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
        assertThat(sourceResult(id, "FNS")).containsEntry("status", "TIMEOUT").containsEntry("attempts", 3);
    }

    /** ИНН, которого нет в ЕГРЮЛ: отказ сразу после обогащения, ФНС и БКИ не спрашиваем. */
    @Test
    void companyMissingInRegistryIsRejected() {
        String id = submit(OTHER_CLIENT, """
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "500000.00",
                 "term": 30, "termUnit": "DAYS", "fundingMode": "ON_DEMAND"}
                """.formatted(FOREIGN_COMPANY));

        var card = awaitDecision(OTHER_CLIENT, id);
        card.extractingPath("$.status").isEqualTo("REJECTED");
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("COMPANY_NOT_FOUND");
        SOURCES.verify(0, getRequestedFor(urlPathEqualTo("/bki/companies/7819034566/credit-report")));
        assertThat(get(OTHER_CLIENT, "/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[*].toStatus").asArray().containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT", "REJECTED");
    }

    /** Непредвиденная ошибка обработки (продукт убрали из каталога, пока шёл запрос к БКИ) — ручная проверка. */
    @Test
    void unexpectedProcessingErrorSendsApplicationToManualReview() {
        SOURCES.stubFor(WireMock.get(urlPathEqualTo("/bki/companies/7841256395/credit-report")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"activeCreditsCount\": 1, \"totalDebtAmount\": \"600000.00\", \"maxOverdueDays12m\": 0}")
                        .withFixedDelay(300)));
        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));
        jdbc.update("UPDATE product.credit_products SET active = false WHERE code = 'LUMP_SUM_LOAN'");
        try {
            var card = awaitDecision(CLIENT, id);
            card.extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
            card.extractingPath("$.reasons[*].code").asArray().containsExactly("PROCESSING_ERROR");
            card.extractingPath("$.reasons[0].message")
                    .isEqualTo("Автоматическая обработка не завершилась — заявку проверит специалист");
        } finally {
            jdbc.update("UPDATE product.credit_products SET active = true WHERE code = 'LUMP_SUM_LOAN'");
        }
        // Решение откатилось целиком: ни балла, ни записи решения, переход — сразу из ENRICHMENT
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scoring.decisions WHERE application_id = ?::uuid",
                Integer.class, id)).isZero();
        assertThat(get(CLIENT, "/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[*].toStatus").asArray()
                .containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT", "MANUAL_REVIEW");
    }

    // --- Подписание ---

    @Test
    void signingCodeIsBlockedAfterAttemptsAndExpires() {
        String id = acceptedOffer();
        assertThat(confirm(id, "1234")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].message").isEqualTo("Сначала запросите код подписания");

        String demoCode = read(post(CLIENT, "/applications/" + id + "/signing/request-code", null), "$.demoCode");
        String wrong = otherThan(demoCode);
        assertThat(confirm(id, wrong)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(confirm(id, wrong)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(confirm(id, wrong)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].message").isEqualTo("Неверный код. Попытки закончились — запросите новый код");
        // Заблокированный код не подходит даже верный
        assertThat(confirm(id, demoCode)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].message").isEqualTo("Попытки закончились — запросите новый код");

        String expiring = read(post(CLIENT, "/applications/" + id + "/signing/request-code", null), "$.demoCode");
        clock.advance(Duration.ofMinutes(5));
        assertThat(confirm(id, expiring)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].field").isEqualTo("code");
        assertThat(confirm(id, expiring)).bodyJson()
                .extractingPath("$.errors[0].message").isEqualTo("Срок действия кода истёк — запросите новый код");

        String fresh = read(post(CLIENT, "/applications/" + id + "/signing/request-code", null), "$.demoCode");
        assertThat(confirm(id, fresh)).hasStatusOk();
        awaitStatus(CLIENT, id, "DISBURSED");
        assertThat(post(CLIENT, "/applications/" + id + "/signing/request-code", null)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void signingIsOnlyForAcceptedOffer() {
        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));
        awaitDecision(CLIENT, id);

        assertThat(post(CLIENT, "/applications/" + id + "/signing/request-code", null)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").isEqualTo("Нельзя запросить код подписания в статусе APPROVED");
        assertThat(confirm(id, "1234")).hasStatus(HttpStatus.CONFLICT);
    }

    // --- Помощники ---

    private String acceptedOffer() {
        String id = submit(CLIENT, SEVERNY_VETER_LOAN.formatted(SEVERNY_VETER, "500000.00", 24));
        awaitDecision(CLIENT, id).extractingPath("$.status").isEqualTo("APPROVED");
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk();
        return id;
    }

    private String submit(String login, String draft) {
        String id = read(post(login, "/applications", draft), "$.id");
        assertThat(post(login, "/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.status").isEqualTo("SUBMITTED");
        return id;
    }

    private AbstractJsonContentAssert<?> awaitDecision(String login, String id) {
        await().atMost(Duration.ofSeconds(15)).until(() -> !IN_PROGRESS.contains(status(login, id)));
        return assertThat(get(login, "/applications/" + id)).hasStatusOk().bodyJson();
    }

    private AbstractJsonContentAssert<?> awaitStatus(String login, String id, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> status.equals(status(login, id)));
        return assertThat(get(login, "/applications/" + id)).hasStatusOk().bodyJson();
    }

    private String status(String login, String id) {
        return read(get(login, "/applications/" + id), "$.status");
    }

    private Integer score(String id) {
        return jdbc.queryForObject("SELECT score FROM scoring.scoring_results WHERE application_id = ?::uuid",
                Integer.class, id);
    }

    private Map<String, Object> sourceResult(String id, String source) {
        return jdbc.queryForMap("""
                SELECT s.status, s.attempts, s.http_status FROM enrichment.source_results s
                JOIN enrichment.enrichments e ON e.id = s.enrichment_id
                WHERE e.application_id = ?::uuid AND s.source = ?""", id, source);
    }

    private MvcTestResult respond(String id, String requestId) {
        return post(CLIENT, "/applications/" + id + "/info-requests/" + requestId + "/response",
                "{\"comment\": \"Добавил выручку и расходы\", \"documentIds\": []}");
    }

    private MvcTestResult confirm(String id, String code) {
        return post(CLIENT, "/applications/" + id + "/signing/confirm", "{\"code\": \"%s\"}".formatted(code));
    }

    private static String otherThan(String code) {
        return code.equals("0000") ? "1111" : "0000";
    }

    private MvcTestResult get(String login, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(login)).exchange();
    }

    private MvcTestResult post(String login, String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(login));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), path);
    }
}
