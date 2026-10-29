package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Бэк-офис андеррайтера: очереди, карточка, ручные решения из {@code MANUAL_REVIEW}, подтверждение предварительного
 * одобрения из {@code PENDING_CONFIRMATION}, запрос сведений. Заявки проходят настоящий конвейер через моки:
 * Морозов — ручная проверка (просрочка 45 дней), ТехноПром — предварительное одобрение 5 млн.
 */
class BackofficeIntegrationTests extends IntegrationTest {

    private static final Set<String> IN_PROGRESS = Set.of("SUBMITTED", "ENRICHMENT", "SCORING");
    private static final String UNDERWRITER_ID = "5c3e7a9d-1f4b-4b2e-8d6a-9e2c4f7b1a38";

    private static final String MOROZOV_LINE = """
            {"companyId": "%s", "productCode": "CREDIT_LINE", "purpose": "WORKING_CAPITAL", "requestedAmount": "1000000.00",
             "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES",
             "financials": {"annualRevenue": "12000000.00", "annualExpenses": "9000000.00", "monthlyDebtPayment": "50000.00"}}
            """.formatted(MOROZOV);
    private static final String TECHNOPROM_LOAN = """
            {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "5000000.00",
             "term": 36, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM", "repaymentType": "ANNUITY",
             "financials": {"annualRevenue": "60000000.00", "annualExpenses": "48000000.00", "monthlyDebtPayment": "200000.00"}}
            """.formatted(TECHNOPROM);

    @Autowired
    JdbcTemplate jdbc;

    // --- Очереди и карточка ---

    @Test
    void reviewQueueShowsLongestWaitingFirst() {
        clock.set(TestcontainersConfiguration.TODAY.plus(Duration.ofHours(1)));
        String first = manualReview();
        clock.set(TestcontainersConfiguration.TODAY.plus(Duration.ofHours(2)));
        String second = manualReview();

        var page = assertThat(get(UNDERWRITER, "/backoffice/applications?queue=REVIEW&size=100")).hasStatusOk().bodyJson();
        page.extractingPath("$.content[*].status").asArray().containsOnly("MANUAL_REVIEW");
        List<String> ids = JsonPath.read(body(get(UNDERWRITER, "/backoffice/applications?queue=REVIEW&size=100")),
                "$.content[*].id");
        assertThat(ids).contains(first, second);
        assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(second));

        String row = "$.content[?(@.id == '%s')]".formatted(first);
        page.extractingPath(row + ".companyName").asArray().containsExactly("ИП Морозов Дмитрий Олегович");
        page.extractingPath(row + ".inn").asArray().containsExactly("526218493785");
        page.extractingPath(row + ".score").asArray().containsExactly(75);
        page.extractingPath(row + ".grade").asArray().containsExactly("B");
        page.extractingPath(row + ".reasons[*].code").asArray().containsExactly("OVERDUE_31_60");
        page.extractingPath(row + ".waitingSince").asArray().containsExactly("2026-11-01T13:00:00+03:00");

        // Очередь подтверждения — только PENDING_CONFIRMATION; фильтр статуса вне очереди — пусто
        assertThat(get(UNDERWRITER, "/backoffice/applications?queue=CONFIRMATION&size=100")).hasStatusOk().bodyJson()
                .extractingPath("$.content[*].id").asArray().doesNotContain(first, second);
        assertThat(get(UNDERWRITER, "/backoffice/applications?queue=REVIEW&status=APPROVED")).hasStatusOk().bodyJson()
                .extractingPath("$.totalElements").isEqualTo(0);
        // ALL — без черновиков
        String draft = read(post(CLIENT, "/applications", MOROZOV_LINE), "$.id");
        List<String> all = JsonPath.read(body(get(UNDERWRITER, "/backoffice/applications?queue=ALL&size=100")),
                "$.content[*].id");
        assertThat(all).contains(second).doesNotContain(draft);

        assertThat(get(UNDERWRITER, "/backoffice/applications?queue=OTHER")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("queue");
    }

    @Test
    void cardShowsSourcesScoringAndHistory() {
        String id = manualReview();

        var card = assertThat(get(UNDERWRITER, "/backoffice/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.application.status").isEqualTo("MANUAL_REVIEW");
        card.extractingPath("$.application.financials.annualRevenue").isEqualTo("12000000.00");
        card.extractingPath("$.application.reasons[*].code").asArray().containsExactly("OVERDUE_31_60");
        card.extractingPath("$.company.inn").isEqualTo("526218493785");
        card.extractingPath("$.company.businessType").isEqualTo("IP");
        card.extractingPath("$.enrichment.sources[*].source").asArray().containsExactly("EGRUL", "FNS", "BKI");
        card.extractingPath("$.enrichment.sources[*].status").asArray().containsOnly("OK");
        card.extractingPath("$.enrichment.maxOverdueDays12m").isEqualTo(45);
        card.extractingPath("$.scoring.score").isEqualTo(75);
        card.extractingPath("$.scoring.grade").isEqualTo("B");
        card.extractingPath("$.scoring.factors[*].factor").asArray().containsExactly(
                "PROFITABILITY", "DEBT_LOAD", "OVERDUE_DAYS", "TAX_DEBT", "BUSINESS_AGE", "ACTIVE_CREDITS");
        card.extractingPath("$.scoring.factors[*].maxPoints").asArray().containsExactly(25, 20, 20, 10, 15, 10);
        List<Integer> points = JsonPath.read(body(get(UNDERWRITER, "/backoffice/applications/" + id)),
                "$.scoring.factors[*].points");
        assertThat(points.stream().mapToInt(Integer::intValue).sum()).isEqualTo(75);
        card.extractingPath("$.infoRequests").asArray().isEmpty();
        card.extractingPath("$.documents").asArray().isEmpty();
        card.extractingPath("$.history[*].toStatus").asArray()
                .containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT", "SCORING", "MANUAL_REVIEW");
        card.extractingPath("$.history[*].actorName").asArray().isEmpty();

        assertThat(get(UNDERWRITER, "/backoffice/applications/00000000-0000-4000-8000-000000000000"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // --- Решения из MANUAL_REVIEW ---

    /** Без условий — сумма и срок из заявки, ставка из каталога; оффер не предварительный, имя сотрудника в истории. */
    @Test
    void underwriterApprovesWithApplicationTerms() {
        String id = manualReview();

        var approved = assertThat(decide(id, "{\"decision\": \"APPROVE\"}")).hasStatusOk().bodyJson();
        approved.extractingPath("$.status").isEqualTo("APPROVED");
        approved.extractingPath("$.reasons").asArray().isEmpty();
        approved.extractingPath("$.offer.productCode").isEqualTo("CREDIT_LINE");
        approved.extractingPath("$.offer.amount").isEqualTo("1000000.00");
        approved.extractingPath("$.offer.term").isEqualTo(6);
        approved.extractingPath("$.offer.rate").isEqualTo(23.5);
        approved.extractingPath("$.offer.preliminary").isEqualTo(false);
        approved.extractingPath("$.offer.expiresAt").isEqualTo("2026-11-08T12:00:00+03:00");

        var card = assertThat(get(UNDERWRITER, "/backoffice/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.history[-1:].actorType").asArray().containsExactly("EMPLOYEE");
        card.extractingPath("$.history[-1:].actorName").asArray().containsExactly("Мария Смирнова");
        card.extractingPath("$.history[-1:].comment").asArray().containsExactly("Одобрено специалистом банка");
        // Клиенту имя сотрудника не показываем
        assertThat(get(CLIENT, "/applications/" + id + "/history")).hasStatusOk().bodyJson()
                .extractingPath("$[*].actorName").asArray().isEmpty();

        assertThat(lastDecision(id)).containsEntry("outcome", "APPROVE").containsEntry("decided_by", "EMPLOYEE")
                .containsEntry("employee_id", UUID.fromString(UNDERWRITER_ID));
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
    }

    /** Лимит по финансам сотрудник может превысить; сумма больше N — предварительного одобрения нет. */
    @Test
    void underwriterMayExceedFinancialLimit() {
        String id = manualReview();

        var approved = assertThat(decide(id, """
                {"decision": "APPROVE", "amount": "8000000.00", "term": 12, "termUnit": "MONTHS",
                 "comment": "Обороты по счёту покрывают нагрузку"}""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
        // Срок транша кредитной линии без подписки — до 6 месяцев: проверка по продукту
        approved.extractingPath("$.errors[*].field").asArray().containsExactly("term");

        var offer = assertThat(decide(id, """
                {"decision": "APPROVE", "amount": "8000000.00", "rate": 25.5,
                 "comment": "Обороты по счёту покрывают нагрузку"}""")).hasStatusOk().bodyJson();
        offer.extractingPath("$.offer.amount").isEqualTo("8000000.00");
        offer.extractingPath("$.offer.rate").isEqualTo(25.5);
        offer.extractingPath("$.offer.preliminary").isEqualTo(false);
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
    }

    @Test
    void termsOutsideProductAreRejected() {
        String id = manualReview();

        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"amount\": \"31000000.00\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("amount");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"rate\": 20}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("Ставка не может быть ниже ставки каталога — 23.5");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"productCode\": \"OVERDRAFT\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("productCode");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"term\": 3}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("termUnit");
        assertThat(decide(id, "{\"decision\": \"COUNTER_OFFER\", \"productCode\": \"OVERDRAFT\", \"amount\": \"500000.00\", "
                + "\"comment\": \"Овердрафт вместо линии\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("term");
        assertThat(decide(id, "{\"decision\": \"COUNTER_OFFER\", \"comment\": \"Снижаем сумму\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");
        assertThat(decide(id, "{\"decision\": \"REJECT\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("comment");

        assertThat(status(UNDERWRITER, id)).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scoring.decisions WHERE application_id = ?::uuid "
                + "AND decided_by = 'EMPLOYEE'", Integer.class, id)).isZero();
    }

    /** Контрпредложение — другим продуктом; клиент видит комментарий сотрудника в причине. */
    @Test
    void underwriterMakesCounterOfferWithAnotherProduct() {
        String id = manualReview();

        var counter = assertThat(decide(id, """
                {"decision": "COUNTER_OFFER", "productCode": "OVERDRAFT", "amount": "500000.00", "term": 60,
                 "termUnit": "DAYS", "comment": "Для кассовых разрывов подойдёт овердрафт"}""")).hasStatusOk().bodyJson();
        counter.extractingPath("$.status").isEqualTo("COUNTER_OFFER");
        counter.extractingPath("$.offer.productCode").isEqualTo("OVERDRAFT");
        counter.extractingPath("$.offer.amount").isEqualTo("500000.00");
        counter.extractingPath("$.offer.termUnit").isEqualTo("DAYS");
        counter.extractingPath("$.offer.rate").isEqualTo(26.0);
        counter.extractingPath("$.reasons[0].code").isEqualTo("MANUAL_COUNTER_OFFER");
        counter.extractingPath("$.reasons[0].message").isEqualTo("Для кассовых разрывов подойдёт овердрафт");
        counter.extractingPath("$.availableActions").asArray().containsExactly("ACCEPT_OFFER", "DECLINE_OFFER");
        assertThat(lastDecision(id)).containsEntry("outcome", "COUNTER_OFFER");
    }

    @Test
    void underwriterRejectsWithComment() {
        String id = manualReview();

        var rejected = assertThat(decide(id, """
                {"decision": "REJECT", "comment": "Просрочка повторяется, обороты падают", "reasonCodes": ["OVERDUE_HISTORY"]}
                """)).hasStatusOk().bodyJson();
        rejected.extractingPath("$.status").isEqualTo("REJECTED");
        rejected.extractingPath("$.reasons[*].code").asArray().containsExactly("MANUAL_REJECT");
        rejected.extractingPath("$.reasons[0].message").isEqualTo("Просрочка повторяется, обороты падают");
        rejected.extractingPath("$.reasons[0].advice")
                .isEqualTo("Подайте новую заявку, когда причина отказа будет устранена");
        rejected.extractingPath("$.availableActions").asArray().isEmpty();
        assertThat(lastDecision(id)).containsEntry("outcome", "REJECT")
                .containsEntry("comment", "Просрочка повторяется, обороты падают");
        assertThat(jdbc.queryForObject("SELECT reason_codes[1] FROM scoring.decisions WHERE application_id = ?::uuid "
                + "AND decided_by = 'EMPLOYEE'", String.class, id)).isEqualTo("OVERDUE_HISTORY");

        // Финальный статус: повторное решение — 409
        assertThat(decide(id, "{\"decision\": \"APPROVE\"}")).hasStatus(HttpStatus.CONFLICT);
    }

    /** Комментарий до 2000 символов (контракт) — текст причины для клиента и уведомления. */
    @Test
    void longCommentAndLongInfoRequestAreSaved() {
        String rejected = manualReview();
        String comment = "Причина ".repeat(250);
        assertThat(comment).hasSize(2000);
        assertThat(decide(rejected, "{\"decision\": \"REJECT\", \"comment\": \"%s\"}".formatted(comment)))
                .hasStatusOk().bodyJson().extractingPath("$.reasons[0].message").isEqualTo(comment.strip());

        String requested = manualReview();
        String message = "Уточните ".repeat(222) + "в";
        assertThat(message).hasSize(1999);
        assertThat(requestInfo(requested, "{\"message\": \"%s\"}".formatted(message))).hasStatus(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(notificationBody(rejected, "DECISION_MADE")).contains(comment.strip());
            assertThat(notificationBody(requested, "INFO_REQUESTED")).contains(message);
        });
    }

    @Test
    void reasonCodesAndRateAreChecked() {
        String id = manualReview();

        assertThat(decide(id, """
                {"decision": "REJECT", "comment": "Высокая нагрузка", "reasonCodes": ["DEBT_LOAD_HIGH", "WHATEVER"]}"""))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("reasonCodes[1]");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"rate\": 61}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("Ставка не может быть выше 60% годовых");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"rate\": 25.555}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("Ставка — не больше двух знаков после запятой");
        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"rate\": 99999}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("rate");
        assertThat(status(UNDERWRITER, id)).isEqualTo("MANUAL_REVIEW");

        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"rate\": 60.00}")).hasStatusOk()
                .bodyJson().extractingPath("$.offer.rate").isEqualTo(60.0);
    }

    /** Запрос сотрудника: поля и документы; ответ клиента возвращает заявку к сотруднику. */
    @Test
    void underwriterRequestsInfoAndClientAnswers() {
        String id = manualReview();

        assertThat(requestInfo(id, """
                {"message": "Уточните выручку", "requestedFields": ["financials.annualRevenue", "requestedAmount"]}"""))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field")
                .isEqualTo("requestedFields[1]");

        MvcTestResult created = requestInfo(id, """
                {"message": "Пришлите отчётность и уточните выручку за год",
                 "requestedFields": ["financials.annualRevenue"], "requestedDocuments": ["FINANCIAL_STATEMENT"]}""");
        var request = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        request.extractingPath("$.createdBy").isEqualTo("EMPLOYEE");
        request.extractingPath("$.status").isEqualTo("OPEN");
        request.extractingPath("$.requestedDocuments").asArray().containsExactly("FINANCIAL_STATEMENT");
        String requestId = read(created, "$.id");

        var client = assertThat(get(CLIENT, "/applications/" + id)).hasStatusOk().bodyJson();
        client.extractingPath("$.status").isEqualTo("NEED_INFO");
        client.extractingPath("$.openInfoRequest.requestedFields").asArray().containsExactly("financials.annualRevenue");
        client.extractingPath("$.availableActions").asArray().containsExactly("RESPOND_INFO", "UPLOAD_DOCUMENT", "CANCEL");

        assertThat(patch(id, "{\"financials\": {\"annualRevenue\": \"13000000.00\"}}")).hasStatusOk();
        String documentId = read(mvc.post().uri("/applications/" + id + "/documents")
                .header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).multipart()
                .file(new MockMultipartFile("file", "report.pdf", "application/pdf",
                        "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII)))
                .param("documentType", "FINANCIAL_STATEMENT").exchange(), "$.id");
        assertThat(post(CLIENT, "/applications/" + id + "/info-requests/" + requestId + "/response",
                "{\"comment\": \"Выручка уточнена\", \"documentIds\": [\"%s\"]}".formatted(documentId))).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("MANUAL_REVIEW");

        var card = assertThat(get(UNDERWRITER, "/backoffice/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.infoRequests[*].status").asArray().containsExactly("ANSWERED");
        card.extractingPath("$.infoRequests[0].response").isEqualTo("Выручка уточнена");
        card.extractingPath("$.application.financials.annualRevenue").isEqualTo("13000000.00");
        assertThat(lastDecision(id)).containsEntry("outcome", "NEED_INFO");
    }

    // --- Подтверждение предварительного одобрения ---

    @Test
    void underwriterConfirmsPreliminaryApprovalAndClientSigns() {
        String id = pendingConfirmation();
        List<String> queue = JsonPath.read(body(get(UNDERWRITER, "/backoffice/applications?queue=CONFIRMATION&size=100")),
                "$.content[*].id");
        assertThat(queue).contains(id);

        assertThat(decide(id, "{\"decision\": \"APPROVE\", \"amount\": \"6000000.00\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");

        var confirmed = assertThat(decide(id, "{\"decision\": \"APPROVE\"}")).hasStatusOk().bodyJson();
        confirmed.extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
        confirmed.extractingPath("$.offer.amount").isEqualTo("5000000.00");
        confirmed.extractingPath("$.availableActions").asArray().containsExactly("SIGN");

        String code = read(post(CLIENT, "/applications/" + id + "/signing/request-code", null), "$.demoCode");
        assertThat(post(CLIENT, "/applications/" + id + "/signing/confirm", "{\"code\": \"%s\"}".formatted(code)))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(10)).until(() -> "DISBURSED".equals(status(CLIENT, id)));
        assertThat(get(CLIENT, "/applications/" + id + "/history")).hasStatusOk().bodyJson()
                .extractingPath("$[*].actorType").asArray().containsExactly("CLIENT", "CLIENT", "SYSTEM", "SYSTEM",
                        "SYSTEM", "CLIENT", "EMPLOYEE", "CLIENT", "SYSTEM");
    }

    @Test
    void underwriterRejectsOrCounterOffersAfterClientAgreed() {
        String rejected = pendingConfirmation();
        var problem = assertThat(decide(rejected, "{\"decision\": \"REJECT\", \"comment\": \"Залоговое обеспечение не подтверждено\"}"))
                .hasStatusOk().bodyJson();
        problem.extractingPath("$.status").isEqualTo("REJECTED");
        problem.extractingPath("$.reasons[0].code").isEqualTo("MANUAL_REJECT");
        // Принятое клиентом предложение после отказа банка не действует
        assertThat(offerStatuses(rejected)).containsExactly("DECLINED");

        String countered = pendingConfirmation();
        var counter = assertThat(decide(countered, """
                {"decision": "COUNTER_OFFER", "amount": "3000000.00", "comment": "Подтверждаем 3 млн"}"""))
                .hasStatusOk().bodyJson();
        counter.extractingPath("$.status").isEqualTo("COUNTER_OFFER");
        counter.extractingPath("$.offer.amount").isEqualTo("3000000.00");
        counter.extractingPath("$.offer.term").isEqualTo(36);
        counter.extractingPath("$.offer.preliminary").isEqualTo(false);
        // Оффер сотрудника клиент принимает без повторного подтверждения
        assertThat(post(CLIENT, "/applications/" + countered + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
        assertThat(offerStatuses(countered)).containsExactly("SUPERSEDED", "ACCEPTED");
    }

    // --- Недопустимые действия и доступ ---

    @Test
    void decisionsOutsideReviewAreConflicts() {
        String id = pendingConfirmation();
        assertThat(requestInfo(id, "{\"message\": \"Уточните выручку\"}")).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").asString().endsWith("/invalid-transition");

        String draft = read(post(CLIENT, "/applications", MOROZOV_LINE), "$.id");
        assertThat(decide(draft, "{\"decision\": \"APPROVE\"}")).hasStatus(HttpStatus.CONFLICT);
        assertThat(decide("00000000-0000-4000-8000-000000000000", "{\"decision\": \"APPROVE\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(requestInfo("00000000-0000-4000-8000-000000000000", "{\"message\": \"?\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void clientHasNoAccessToBackoffice() {
        String id = manualReview();
        String base = "/backoffice/applications/" + id;

        assertThat(get(CLIENT, "/backoffice/applications?queue=REVIEW")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(CLIENT, base)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post(CLIENT, base + "/decision", "{\"decision\": \"APPROVE\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post(CLIENT, base + "/info-requests", "{\"message\": \"?\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(status(CLIENT, id)).isEqualTo("MANUAL_REVIEW");
    }

    // --- Помощники ---

    private String manualReview() {
        String id = submit(MOROZOV_LINE);
        awaitDecision(id).extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
        return id;
    }

    private String pendingConfirmation() {
        String id = submit(TECHNOPROM_LOAN);
        awaitDecision(id).extractingPath("$.offer.preliminary").isEqualTo(true);
        assertThat(post(CLIENT, "/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("PENDING_CONFIRMATION");
        return id;
    }

    private String submit(String draft) {
        String id = read(post(CLIENT, "/applications", draft), "$.id");
        assertThat(post(CLIENT, "/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED);
        return id;
    }

    private AbstractJsonContentAssert<?> awaitDecision(String id) {
        await().atMost(Duration.ofSeconds(15)).until(() -> !IN_PROGRESS.contains(status(CLIENT, id)));
        return assertThat(get(CLIENT, "/applications/" + id)).hasStatusOk().bodyJson();
    }

    /** Решение сотрудника в журнале решений; у тестовых часов время одно, поэтому ищем по автору. */
    private Map<String, Object> lastDecision(String id) {
        return jdbc.queryForMap("""
                SELECT outcome, decided_by, employee_id, comment FROM scoring.decisions
                WHERE application_id = ?::uuid AND decided_by = 'EMPLOYEE'""", id);
    }

    private List<String> offerStatuses(String id) {
        return jdbc.queryForList("SELECT status FROM application.offers WHERE application_id = ?::uuid ORDER BY created_at, "
                + "CASE status WHEN 'SUPERSEDED' THEN 0 ELSE 1 END", String.class, id);
    }

    private String notificationBody(String id, String type) {
        return jdbc.queryForObject("SELECT body FROM notification.notifications WHERE application_id = ?::uuid AND type = ?",
                String.class, id, type);
    }

    private String status(String login, String id) {
        String uri = login.equals(UNDERWRITER) ? "/backoffice/applications/" + id : "/applications/" + id;
        return read(get(login, uri), login.equals(UNDERWRITER) ? "$.application.status" : "$.status");
    }

    private MvcTestResult decide(String id, String json) {
        return post(UNDERWRITER, "/backoffice/applications/" + id + "/decision", json);
    }

    private MvcTestResult requestInfo(String id, String json) {
        return post(UNDERWRITER, "/backoffice/applications/" + id + "/info-requests", json);
    }

    private MvcTestResult patch(String id, String json) {
        return mvc.patch().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
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

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(body(result), path);
    }
}
