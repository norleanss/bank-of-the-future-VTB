package ru.creditconveyor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static ru.creditconveyor.application.ApplicationStatus.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import ru.creditconveyor.IntegrationTest;

/**
 * Действия клиента — через API, переходы системы и андеррайтера — через {@link ApplicationStateMachine},
 * как их выполняют другие модули. Чтобы вести заявку по статусам вручную, её отправляют в обход конвейера
 * ({@link #submittedApplication()}); сквозная обработка — в {@code ConveyorIntegrationTests}.
 */
class ApplicationLifecycleIntegrationTests extends IntegrationTest {

    private static final String COMPLETE_DRAFT = """
            {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "800000.00",
             "term": 60, "termUnit": "DAYS", "fundingMode": "ON_DEMAND", "subscription": true}
            """.formatted(SEVERNY_VETER);

    private static final UUID CLIENT_ID = UUID.fromString("7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f");
    private static final UUID EMPLOYEE_ID = UUID.fromString("5c3e7a9d-1f4b-4b2e-8d6a-9e2c4f7b1a38");

    @Autowired
    ApplicationStateMachine stateMachine;

    @Autowired
    OfferExpiryJob offerExpiryJob;

    @Autowired
    LoanApplicationRepository applications;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    SigningCodeRepository signingCodes;

    @Autowired
    JdbcTemplate jdbc;

    // --- Черновик ---

    @Test
    void createsDraftWithActionsAndHistory() {
        MvcTestResult created = post("/applications", COMPLETE_DRAFT);

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.status").isEqualTo("DRAFT");
        body.extractingPath("$.number").asString().matches("CR-2026-\\d{6}");
        body.extractingPath("$.companyId").isEqualTo(SEVERNY_VETER);
        body.extractingPath("$.requestedAmount").isEqualTo("800000.00");
        body.extractingPath("$.term").isEqualTo(60);
        body.extractingPath("$.termUnit").isEqualTo("DAYS");
        body.extractingPath("$.subscription").isEqualTo(true);
        body.extractingPath("$.availableActions").asArray().containsExactly("EDIT", "SUBMIT", "CANCEL");
        body.extractingPath("$.nextStepHint").isEqualTo("Заполните анкету и отправьте заявку");
        body.extractingPath("$.createdAt").isEqualTo("2026-11-01T12:00:00+03:00");
        // Поля без значения в ответе отсутствуют, а не равны null
        body.doesNotHavePath("$.financials");
        body.doesNotHavePath("$.repaymentType");
        body.doesNotHavePath("$.offer");
        body.doesNotHavePath("$.openInfoRequest");
        body.doesNotHavePath("$.submittedAt");

        var history = assertThat(get("/applications/" + id(created) + "/history")).hasStatusOk().bodyJson();
        history.extractingPath("$").asArray().hasSize(1);
        history.doesNotHavePath("$[0].fromStatus");
        history.extractingPath("$[0].toStatus").isEqualTo("DRAFT");
        history.extractingPath("$[0].actorType").isEqualTo("CLIENT");
    }

    @Test
    void createRequiresOwnCompany() {
        assertThat(post("/applications", "{\"companyId\": \"%s\"}".formatted(FOREIGN_COMPANY)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(post("/applications", "{\"purpose\": \"CASH_GAP\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("companyId");
    }

    @Test
    void rejectsInvalidDraftValues() {
        var body = assertThat(post("/applications", """
                {"companyId": "%s", "requestedAmount": "0.00", "term": 3, "collateral": true}
                """.formatted(SEVERNY_VETER)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
        body.extractingPath("$.errors[*].field").asArray().containsExactly("requestedAmount", "termUnit", "collateral");
    }

    // --- PATCH: JSON Merge Patch ---

    @Test
    void patchLeavesFieldsThatWereNotSent() {
        String id = id(post("/applications", """
                {"companyId": "%s", "purpose": "EQUIPMENT", "subscription": true,
                 "financials": {"annualRevenue": "17434414.00"}}
                """.formatted(SEVERNY_VETER)));

        var body = assertThat(patch(id, "{\"repaymentType\": \"ANNUITY\"}")).hasStatusOk().bodyJson();
        body.extractingPath("$.repaymentType").isEqualTo("ANNUITY");
        body.extractingPath("$.purpose").isEqualTo("EQUIPMENT");
        body.extractingPath("$.subscription").isEqualTo(true);
        body.extractingPath("$.financials.annualRevenue").isEqualTo("17434414.00");
    }

    @Test
    void patchNullClearsField() {
        String id = id(post("/applications", COMPLETE_DRAFT));

        var body = assertThat(patch(id, "{\"purpose\": null, \"requestedAmount\": null, \"term\": null, \"termUnit\": null}"))
                .hasStatusOk().bodyJson();
        body.doesNotHavePath("$.purpose");
        body.doesNotHavePath("$.requestedAmount");
        body.doesNotHavePath("$.term");
        body.doesNotHavePath("$.termUnit");
        body.extractingPath("$.productCode").isEqualTo("OVERDRAFT");
    }

    @Test
    void patchMergesNestedFinancials() {
        String id = id(post("/applications", """
                {"companyId": "%s", "financials": {"annualRevenue": "17434414.00", "annualExpenses": "10468403.00"}}
                """.formatted(SEVERNY_VETER)));

        var merged = assertThat(patch(id, "{\"financials\": {\"bankMonthlyTurnover\": \"1450000.00\", \"annualExpenses\": null}}"))
                .hasStatusOk().bodyJson();
        merged.extractingPath("$.financials.annualRevenue").isEqualTo("17434414.00");
        merged.extractingPath("$.financials.bankMonthlyTurnover").isEqualTo("1450000.00");
        merged.doesNotHavePath("$.financials.annualExpenses");

        assertThat(patch(id, "{\"financials\": null}")).hasStatusOk().bodyJson().doesNotHavePath("$.financials");
    }

    @Test
    void patchValidatesFieldsWithDottedPaths() {
        String id = id(post("/applications", COMPLETE_DRAFT));

        assertThat(patch(id, "{\"financials\": {\"annualRevenue\": \"12\"}}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("financials.annualRevenue");
        assertThat(patch(id, "{\"term\": 3}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("termUnit");
        assertThat(patch(id, "{\"subscription\": null}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].message").isEqualTo("Поле нельзя очистить");
        assertThat(patch(id, "{\"companyId\": \"%s\"}".formatted(MOROZOV)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("companyId");
        // Ничего не поменялось
        assertThat(get("/applications/" + id)).bodyJson().extractingPath("$.term").isEqualTo(60);
    }

    // --- Отправка и отмена ---

    @Test
    void submitRequiresSelectionFields() {
        String id = id(post("/applications", "{\"companyId\": \"%s\", \"purpose\": \"CASH_GAP\"}".formatted(SEVERNY_VETER)));

        var body = assertThat(post("/applications/" + id + "/submit", null))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
        body.extractingPath("$.errors[*].field").asArray()
                .containsExactly("productCode", "requestedAmount", "term", "fundingMode");
        assertThat(stateMachine.statusOf(UUID.fromString(id))).isEqualTo(DRAFT);
    }

    @Test
    void submitChecksParametersAgainstProduct() {
        // Сумма больше лимита овердрафта
        assertSubmitRejected("""
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "25000000.00",
                 "term": 30, "termUnit": "DAYS", "fundingMode": "ON_DEMAND"}
                """.formatted(SEVERNY_VETER), "requestedAmount", "Максимальная сумма — 20 000 000 ₽");
        // Кредиту одной суммой нужен срок от 12 месяцев; 300 дней — меньше
        assertSubmitRejected("""
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "2000000.00",
                 "term": 300, "termUnit": "DAYS", "fundingMode": "LUMP_SUM"}
                """.formatted(TECHNOPROM), "term", "Минимальный срок кредита — 12 месяцев");
        // Огромный срок: в днях не помещается в int, но это «слишком длинный», а не «слишком короткий»
        assertSubmitRejected("""
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "2000000.00",
                 "term": 143165577, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM"}
                """.formatted(TECHNOPROM), "term", "Максимальный срок кредита — 60 месяцев");
        // 75 дней овердрафта — только с подпиской
        assertSubmitRejected("""
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "800000.00",
                 "term": 75, "termUnit": "DAYS", "fundingMode": "ON_DEMAND", "subscription": false}
                """.formatted(SEVERNY_VETER), "term",
                "Такой срок — только с подпиской. Транш до 60 дней; с подпиской — до 90 дней, ставка от 13% вместо 26%, комиссия 1% от суммы");
    }

    @Test
    void submitAcceptsLongerTermWithSubscription() {
        String id = id(post("/applications", """
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "800000.00",
                 "term": 3, "termUnit": "MONTHS", "fundingMode": "ON_DEMAND", "subscription": true}
                """.formatted(SEVERNY_VETER)));

        assertThat(post("/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.status").isEqualTo("SUBMITTED");
        awaitDecision(UUID.fromString(id));
    }

    /** Ответ — сразу {@code SUBMITTED}, решение приходит асинхронно; повторная отправка заявку не трогает. */
    @Test
    void submitIsIdempotent() {
        String id = id(post("/applications", COMPLETE_DRAFT));

        var first = assertThat(post("/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED).bodyJson();
        first.extractingPath("$.status").isEqualTo("SUBMITTED");
        first.extractingPath("$.availableActions").asArray().isEmpty();
        first.extractingPath("$.submittedAt").isNotNull();
        assertThat(post("/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED);
        awaitDecision(UUID.fromString(id));
        assertThat(post("/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED);

        assertThat(get("/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[*].toStatus").asArray()
                .startsWith("DRAFT", "SUBMITTED", "ENRICHMENT")
                .containsOnlyOnce("SUBMITTED");
    }

    @Test
    void rejectsActionsNotAllowedInCurrentStatus() {
        String id = submittedApplication().toString();

        var patchBody = assertThat(patch(id, "{\"subscription\": false}"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
        patchBody.extractingPath("$.title").isEqualTo("Действие недоступно");
        patchBody.extractingPath("$.detail").isEqualTo("Нельзя изменить заявку в статусе SUBMITTED");
        patchBody.extractingPath("$.type").isEqualTo("https://credit.example/problems/invalid-transition");

        assertThat(post("/applications/" + id + "/cancel", null)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").isEqualTo("Нельзя отменить заявку в статусе SUBMITTED");
        assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").isEqualTo("Нельзя принять предложение в статусе SUBMITTED");
    }

    @Test
    void cancelsDraft() {
        String id = id(post("/applications", "{\"companyId\": \"%s\"}".formatted(SEVERNY_VETER)));

        var body = assertThat(post("/applications/" + id + "/cancel", null)).hasStatusOk().bodyJson();
        body.extractingPath("$.status").isEqualTo("CANCELLED");
        body.extractingPath("$.availableActions").asArray().isEmpty();

        assertThat(post("/applications/" + id + "/submit", null)).hasStatus(HttpStatus.CONFLICT);
        assertThat(post("/applications/" + id + "/cancel", null)).hasStatus(HttpStatus.CONFLICT);
    }

    // --- Предложение ---

    @Test
    void approvedApplicationShowsOfferAndClientAcceptsIt() {
        UUID id = approvedApplication(false);

        var card = assertThat(get("/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.status").isEqualTo("APPROVED");
        card.extractingPath("$.availableActions").asArray().containsExactly("ACCEPT_OFFER", "DECLINE_OFFER");
        card.extractingPath("$.nextStepHint")
                .isEqualTo("Одобрен лимит 800 000 ₽, транши до 60 дней. Примите предложение до 08.11.2026");
        card.extractingPath("$.offer.termKind").isEqualTo("TRANCHE");
        card.extractingPath("$.offer.amount").isEqualTo("800000.00");
        card.extractingPath("$.offer.term").isEqualTo(60);
        card.extractingPath("$.offer.termUnit").isEqualTo("DAYS");
        card.extractingPath("$.offer.agreementTermMonths").isEqualTo(12);
        card.extractingPath("$.offer.subscription").isEqualTo(true);
        card.extractingPath("$.offer.subscriptionFee").isEqualTo("8000.00");
        card.extractingPath("$.offer.monthlyPayment").isEqualTo("8666.67");
        card.extractingPath("$.offer.preliminary").isEqualTo(false);
        card.extractingPath("$.offer.expiresAt").isEqualTo("2026-11-08T12:00:00+03:00");

        var accepted = assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatusOk().bodyJson();
        accepted.extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
        accepted.extractingPath("$.availableActions").asArray().containsExactly("SIGN");
        accepted.extractingPath("$.offer.amount").isEqualTo("800000.00");
    }

    @Test
    void clientDeclinesOffer() {
        UUID id = approvedApplication(false);

        var declined = assertThat(post("/applications/" + id + "/offer/decline", "{\"comment\": \"Нашли дешевле\"}"))
                .hasStatusOk().bodyJson();
        declined.extractingPath("$.status").isEqualTo("OFFER_DECLINED");
        declined.doesNotHavePath("$.offer");
        assertThat(get("/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[-1].comment").isEqualTo("Нашли дешевле");
    }

    /** Весь путь до выдачи через предварительное одобрение; после согласия клиента предложение не истекает. */
    @Test
    void goesAllTheWayToDisbursementWithPreliminaryApproval() {
        UUID id = approvedApplication(true);
        assertThat(get("/applications/" + id)).bodyJson().extractingPath("$.nextStepHint")
                .isEqualTo("Предварительно одобрен лимит 800 000 ₽, транши до 60 дней. Примите предложение до 08.11.2026"
                        + " — после этого условия подтвердит специалист банка");

        assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("PENDING_CONFIRMATION");
        clock.advance(Duration.ofDays(30));
        offerExpiryJob.runOnce();
        assertThat(stateMachine.statusOf(id)).isEqualTo(PENDING_CONFIRMATION);
        assertThat(get("/applications/" + id)).bodyJson().extractingPath("$.status").isEqualTo("PENDING_CONFIRMATION");

        stateMachine.transition(id, OFFER_ACCEPTED, Actor.employee(EMPLOYEE_ID), "Условия подтверждены");
        String code = JsonPath.read(content(post("/applications/" + id + "/signing/request-code", null)), "$.demoCode");
        assertThat(post("/applications/" + id + "/signing/confirm", "{\"code\": \"%s\"}".formatted(code))).hasStatusOk();
        await().atMost(Duration.ofSeconds(15)).until(() -> stateMachine.statusOf(id) == DISBURSED);

        var card = assertThat(get("/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.status").isEqualTo("DISBURSED");
        card.extractingPath("$.availableActions").asArray().isEmpty();
        card.extractingPath("$.nextStepHint")
                .isEqualTo("Лимит открыт — берите транши на счёт компании по мере необходимости");
        card.extractingPath("$.offer.preliminary").isEqualTo(true);

        var history = assertThat(get("/applications/" + id + "/history")).hasStatusOk().bodyJson();
        history.extractingPath("$[*].toStatus").asArray().containsExactly(
                "DRAFT", "SUBMITTED", "ENRICHMENT", "SCORING", "APPROVED",
                "PENDING_CONFIRMATION", "OFFER_ACCEPTED", "SIGNED", "DISBURSED");
        history.extractingPath("$[*].actorType").asArray().containsExactly(
                "CLIENT", "CLIENT", "SYSTEM", "SYSTEM", "SYSTEM", "CLIENT", "EMPLOYEE", "CLIENT", "SYSTEM");
        history.extractingPath("$[6].comment").isEqualTo("Условия подтверждены");
    }

    @Test
    void underwriterCounterOfferReplacesConditions() {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, MANUAL_REVIEW, Actor.system(), "Просрочка 45 дней");
        stateMachine.offer(id, COUNTER_OFFER, StatusTransitionsTest.terms(false), Actor.employee(EMPLOYEE_ID), "Снижаем лимит");

        var card = assertThat(get("/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.status").isEqualTo("COUNTER_OFFER");
        card.extractingPath("$.availableActions").asArray().containsExactly("ACCEPT_OFFER", "DECLINE_OFFER");
        card.extractingPath("$.offer.amount").isEqualTo("800000.00");
    }

    // --- Истечение предложения ---

    @Test
    void offerExpiresWhenApplicationIsRead() {
        UUID id = approvedApplication(false);
        clock.advance(Duration.ofDays(7));

        var card = assertThat(get("/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.status").isEqualTo("OFFER_EXPIRED");
        card.extractingPath("$.availableActions").asArray().isEmpty();
        card.extractingPath("$.nextStepHint").isEqualTo("Срок действия предложения истёк. Вы можете подать новую заявку");
        card.extractingPath("$.offer.expiresAt").isEqualTo("2026-11-08T12:00:00+03:00");

        var last = assertThat(get("/applications/" + id + "/history")).bodyJson();
        last.extractingPath("$[-1].fromStatus").isEqualTo("APPROVED");
        last.extractingPath("$[-1].actorType").isEqualTo("SYSTEM");
        last.extractingPath("$[-1].comment").isEqualTo("Срок предложения истёк");
    }

    @Test
    void offerIsStillValidJustBeforeDeadline() {
        UUID id = approvedApplication(false);
        clock.advance(Duration.ofDays(7).minusSeconds(1));

        assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("OFFER_ACCEPTED");
    }

    @Test
    void acceptingExpiredOfferIsConflictAndExpiryIsSaved() {
        UUID id = approvedApplication(false);
        clock.advance(Duration.ofDays(8));

        var problem = assertThat(post("/applications/" + id + "/offer/accept", null))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
        problem.extractingPath("$.type").isEqualTo("https://credit.example/problems/offer-expired");
        problem.extractingPath("$.title").isEqualTo("Срок предложения истёк");
        problem.extractingPath("$.detail").isEqualTo("Предложение действовало до 08.11.2026. Вы можете подать новую заявку");
        problem.extractingPath("$.expiresAt").isEqualTo("2026-11-08T12:00+03:00");
        // Переход в OFFER_EXPIRED сохранён, хотя сам запрос завершился ошибкой
        assertThat(stateMachine.statusOf(id)).isEqualTo(OFFER_EXPIRED);

        assertThat(post("/applications/" + id + "/offer/decline", null)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("https://credit.example/problems/offer-expired");
    }

    @Test
    void backgroundJobExpiresOffersNobodyLooksAt() {
        UUID id = approvedApplication(false);
        clock.advance(Duration.ofDays(8));

        assertThat(offerExpiryJob.runOnce()).isPositive();
        assertThat(stateMachine.statusOf(id)).isEqualTo(OFFER_EXPIRED);
        // Повторный запуск ничего не находит: все просроченные уже переведены
        assertThat(offerExpiryJob.runOnce()).isZero();
    }

    @Test
    void stateMachineChecksWhoMakesTheTransitionAndRequiresTerms() {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, MANUAL_REVIEW, Actor.system(), null);

        // Решение по ручной проверке принимает только сотрудник банка, одобрение — только с условиями
        assertThatThrownBy(() -> stateMachine.offer(id, APPROVED, StatusTransitionsTest.terms(false), Actor.system(), null))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> stateMachine.transition(id, APPROVED, Actor.employee(EMPLOYEE_ID), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stateMachine.transition(id, OFFER_EXPIRED, Actor.system(), null))
                .isInstanceOf(IllegalArgumentException.class);
        // Запрос сведений — только с самим запросом; отправка, начало проверки и подписание — только своим путём
        assertThatThrownBy(() -> stateMachine.transition(id, NEED_INFO, Actor.employee(EMPLOYEE_ID), null))
                .isInstanceOf(IllegalArgumentException.class);
        for (ApplicationStatus ownPath : List.of(SUBMITTED, ENRICHMENT, SIGNED)) {
            assertThatThrownBy(() -> stateMachine.transition(id, ownPath, Actor.system(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        stateMachine.offer(id, APPROVED, StatusTransitionsTest.terms(false), Actor.employee(EMPLOYEE_ID), null);
        assertThat(stateMachine.statusOf(id)).isEqualTo(APPROVED);
        // Ответ клиента на предложение — только через API заявок
        assertThatThrownBy(() -> stateMachine.transition(id, OFFER_ACCEPTED, Actor.client(CLIENT_ID), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Ручная проверка и запрос сведений ---

    @Test
    void clientCancelsDuringManualReview() {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, MANUAL_REVIEW, Actor.system(), "БКИ недоступно");

        assertThat(get("/applications/" + id)).bodyJson()
                .extractingPath("$.availableActions").asArray().containsExactly("CANCEL");
        assertThat(post("/applications/" + id + "/cancel", null)).hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void clientEditsWhenBankAsksForInformation() {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, SCORING, Actor.system(), null);
        stateMachine.requestInfo(id, "Укажите выручку за год", List.of("financials.annualRevenue"), Actor.system(),
                "Нет выручки", List.of());

        var card = assertThat(get("/applications/" + id)).bodyJson();
        card.extractingPath("$.availableActions").asArray().containsExactly("RESPOND_INFO", "UPLOAD_DOCUMENT", "CANCEL");
        card.extractingPath("$.openInfoRequest.requestedFields").asArray().containsExactly("financials.annualRevenue");
        card.extractingPath("$.openInfoRequest.createdBy").isEqualTo("SYSTEM");
        card.extractingPath("$.openInfoRequest.status").isEqualTo("OPEN");
        assertThat(patch(id.toString(), "{\"financials\": {\"annualRevenue\": \"9000000.00\"}}"))
                .hasStatusOk().bodyJson().extractingPath("$.financials.annualRevenue").isEqualTo("9000000.00");
    }

    /** После отправки параметры заявки не меняются: в ответ на запрос — только запрошенные поля и финансы. */
    @Test
    void needInfoAllowsOnlyRequestedFieldsAndFinancials() {
        UUID id = needInfo(Actor.system(), List.of("financials.annualRevenue", "repaymentType"));

        var locked = assertThat(patch(id.toString(), """
                {"requestedAmount": "100000.00", "term": 2, "termUnit": "MONTHS", "subscription": false,
                 "productCode": null, "purpose": "EQUIPMENT"}""")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
        locked.extractingPath("$.detail").isEqualTo("Можно изменить только сведения, которые запросил банк");
        locked.extractingPath("$.errors[*].field").asArray()
                .containsExactly("productCode", "purpose", "requestedAmount", "term", "termUnit", "subscription");
        locked.extractingPath("$.errors[0].message").isEqualTo("Поле нельзя изменить после отправки заявки");
        locked.extractingPath("$.errors[1].message").isEqualTo("Банк не запрашивал это поле");

        // Те же значения — не изменение; запрошенное поле и любые финансы — можно
        assertThat(patch(id.toString(), """
                {"productCode": "OVERDRAFT", "requestedAmount": "800000.00", "repaymentType": "INTEREST_ONLY",
                 "financials": {"annualRevenue": "9000000.00", "bankMonthlyTurnover": "700000.00"}}"""))
                .hasStatusOk().bodyJson().extractingPath("$.repaymentType").isEqualTo("INTEREST_ONLY");
        assertThat(stateMachine.statusOf(id)).isEqualTo(NEED_INFO);
    }

    /**
     * Продукт сняли с каталога, пока клиент отвечал: исправить он это не может (продукт, сумма и срок заблокированы),
     * поэтому ответ принимается, а заявка уходит к андеррайтеру.
     */
    @Test
    void answerWithChangedProductConditionsGoesToManualReview() {
        UUID id = needInfo(Actor.system(), List.of("financials.annualRevenue"));
        assertThat(patch(id.toString(), "{\"financials\": {\"annualRevenue\": \"9000000.00\"}}")).hasStatusOk();
        String requestId = JsonPath.read(content(get("/applications/" + id)), "$.openInfoRequest.id");

        jdbc.update("UPDATE product.credit_products SET active = false WHERE code = 'OVERDRAFT'");
        try {
            var card = assertThat(post("/applications/" + id + "/info-requests/" + requestId + "/response", "{}"))
                    .hasStatusOk().bodyJson();
            card.extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
            card.extractingPath("$.reasons[*].code").asArray().containsExactly("PRODUCT_CONDITIONS_CHANGED");
            card.extractingPath("$.reasons[0].message").isEqualTo(
                    "Условия продукта изменились после отправки заявки (Продукт недоступен) — заявку рассмотрит специалист");
            card.doesNotHavePath("$.openInfoRequest");
        } finally {
            jdbc.update("UPDATE product.credit_products SET active = true WHERE code = 'OVERDRAFT'");
        }
        assertThat(get("/applications/" + id + "/history")).bodyJson()
                .extractingPath("$[-1].actorType").isEqualTo("CLIENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrichment.enrichments WHERE application_id = ?",
                Integer.class, id)).isZero();
    }

    /** Сведения запросил андеррайтер — ответ возвращает заявку к нему, автоматического решения нет. */
    @Test
    void answerToUnderwriterRequestReturnsToManualReview() {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, MANUAL_REVIEW, Actor.system(), "Просрочка 45 дней");
        stateMachine.requestInfo(id, "Укажите выручку за год", List.of("financials.annualRevenue"),
                Actor.employee(EMPLOYEE_ID), "Нужна выручка", List.of());
        assertThat(get("/applications/" + id)).bodyJson().extractingPath("$.openInfoRequest.createdBy").isEqualTo("EMPLOYEE");
        assertThat(patch(id.toString(), "{\"financials\": {\"annualRevenue\": \"9000000.00\"}}")).hasStatusOk();
        String requestId = JsonPath.read(content(get("/applications/" + id)), "$.openInfoRequest.id");
        // В обход ответа на запрос — нельзя: запрос остался бы открытым
        assertThatThrownBy(() -> stateMachine.transition(id, MANUAL_REVIEW, Actor.client(CLIENT_ID), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(post("/applications/" + id + "/info-requests/" + requestId + "/response", "{\"comment\": \"Готово\"}"))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("MANUAL_REVIEW");
        var history = assertThat(get("/applications/" + id + "/history")).bodyJson();
        history.extractingPath("$[*].toStatus").asArray().endsWith("NEED_INFO", "MANUAL_REVIEW");
        history.extractingPath("$[-1].actorType").isEqualTo("CLIENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrichment.enrichments WHERE application_id = ?",
                Integer.class, id)).isZero();
    }

    /** Из одновременных вводов кода второй получает конфликт версий — попытка не теряется. */
    @Test
    void signingCodeIsProtectedByVersion() {
        UUID id = approvedApplication(false);
        assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatusOk();
        assertThat(post("/applications/" + id + "/signing/request-code", null)).hasStatusOk();
        SigningCode first = transactions.execute(status -> signingCodes.findByApplicationIdAndStatus(id, SigningCode.ACTIVE).orElseThrow());
        SigningCode second = transactions.execute(status -> signingCodes.findByApplicationIdAndStatus(id, SigningCode.ACTIVE).orElseThrow());

        first.check("0000", clock.instant(), 3);
        transactions.executeWithoutResult(status -> signingCodes.save(first));
        second.check("1111", clock.instant(), 3);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> signingCodes.save(second)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /** Двойной клик «Получить код»: каждый ответ — код или 409, действующий код один. */
    @Test
    void concurrentSigningCodeRequestsDoNotFail() throws Exception {
        UUID id = approvedApplication(false);
        assertThat(post("/applications/" + id + "/offer/accept", null)).hasStatusOk();
        String token = bearer(CLIENT);

        List<Integer> statuses;
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(executor.submit(() -> mvc.post().uri("/applications/" + id + "/signing/request-code")
                        .header(HttpHeaders.AUTHORIZATION, token).exchange().getResponse().getStatus()));
            }
            statuses = new ArrayList<>();
            for (Future<Integer> call : calls) {
                statuses.add(call.get());
            }
        }
        assertThat(statuses).containsAnyOf(200).allMatch(status -> status == 200 || status == 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application.signing_codes WHERE application_id = ? AND status = 'ACTIVE'",
                Integer.class, id)).isEqualTo(1);
    }

    // --- Блокировка и список ---

    /** Два изменения одной и той же версии заявки: второе отклоняется, данные первого не теряются. */
    @Test
    void optimisticLockRejectsStaleUpdate() {
        UUID id = UUID.fromString(id(post("/applications", "{\"companyId\": \"%s\"}".formatted(SEVERNY_VETER))));
        LoanApplication stale = transactions.execute(status -> applications.findById(id).orElseThrow());

        transactions.executeWithoutResult(status -> applications.findById(id).orElseThrow()
                .applyChanges(subscription(true), stale.getUpdatedAt()));

        stale.applyChanges(subscription(false), stale.getUpdatedAt());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> applications.save(stale)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        Boolean saved = transactions.execute(status -> applications.findById(id).orElseThrow().isSubscription());
        assertThat(saved).isTrue();
    }

    @Test
    void listsApplicationsOfAllOwnCompaniesWithFilter() {
        String inSever = id(post("/applications", "{\"companyId\": \"%s\"}".formatted(SEVERNY_VETER)));
        String inMorozov = id(post("/applications", "{\"companyId\": \"%s\"}".formatted(MOROZOV)));

        var all = assertThat(get("/applications?size=100")).hasStatusOk().bodyJson();
        all.extractingPath("$.content[*].id").asArray().contains(inSever, inMorozov);
        all.extractingPath("$.content[?(@.id == '%s')].companyName".formatted(inMorozov)).asArray()
                .containsExactly("ИП Морозов Дмитрий Олегович");
        all.extractingPath("$.page").isEqualTo(0);
        all.extractingPath("$.size").isEqualTo(100);

        var filtered = assertThat(get("/applications?size=100&companyId=" + MOROZOV)).hasStatusOk().bodyJson();
        filtered.extractingPath("$.content[*].id").asArray().contains(inMorozov).doesNotContain(inSever);
        filtered.extractingPath("$.content[*].companyId").asArray().containsOnly(MOROZOV);

        assertThat(get("/applications?companyId=" + FOREIGN_COMPANY)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/applications?size=100").header(HttpHeaders.AUTHORIZATION, bearer(OTHER_CLIENT)))
                .hasStatusOk().bodyJson().extractingPath("$.content[*].id").asArray().doesNotContain(inSever, inMorozov);
    }

    @Test
    void listShowsExpiredOffers() {
        UUID id = approvedApplication(false);
        clock.advance(Duration.ofDays(8));

        assertThat(get("/applications?size=100&companyId=" + SEVERNY_VETER)).hasStatusOk().bodyJson()
                .extractingPath("$.content[?(@.id == '%s')].status".formatted(id)).asArray()
                .containsExactly("OFFER_EXPIRED");
    }

    // --- Помощники ---

    private void assertSubmitRejected(String draft, String field, String message) {
        String id = id(post("/applications", draft));
        var problem = assertThat(post("/applications/" + id + "/submit", null))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        problem.extractingPath("$.detail").isEqualTo("Параметры заявки не подходят под условия продукта");
        problem.extractingPath("$.errors[*].field").asArray().containsExactly(field);
        problem.extractingPath("$.errors[0].message").isEqualTo(message);
        assertThat(stateMachine.statusOf(UUID.fromString(id))).isEqualTo(DRAFT);
    }

    /** {@code NEED_INFO} с открытым запросом от {@code requestedBy}; заявка — в обход конвейера. */
    private UUID needInfo(Actor requestedBy, List<String> fields) {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        if (requestedBy.type() == ActorType.EMPLOYEE) {
            stateMachine.transition(id, MANUAL_REVIEW, Actor.system(), null);
        } else {
            stateMachine.transition(id, SCORING, Actor.system(), null);
        }
        stateMachine.requestInfo(id, "Дозаполните анкету", fields, requestedBy, null, List.of());
        return id;
    }

    /** Отправлена в обход конвейера (без события): дальше тест ведёт заявку по статусам сам. */
    private UUID submittedApplication() {
        UUID id = UUID.fromString(id(post("/applications", COMPLETE_DRAFT)));
        transactions.executeWithoutResult(status -> {
            LoanApplication application = applications.findById(id).orElseThrow();
            application.changeStatus(SUBMITTED, Actor.client(CLIENT_ID), null, clock.instant());
            applications.save(application);
        });
        return id;
    }

    /** Конвейер работает в фоне — дожидаемся решения, чтобы он не пересекался со следующим тестом. */
    private void awaitDecision(UUID id) {
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !EnumSet.of(SUBMITTED, ENRICHMENT, SCORING).contains(stateMachine.statusOf(id)));
    }

    /** Одобрена системой; оффер действует 7 дней. */
    private UUID approvedApplication(boolean preliminary) {
        UUID id = submittedApplication();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, SCORING, Actor.system(), null);
        stateMachine.offer(id, APPROVED, StatusTransitionsTest.terms(preliminary), Actor.system(), null);
        return id;
    }

    private static DraftChanges subscription(boolean value) {
        return new DraftChanges(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.set(value));
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private MvcTestResult patch(String id, String json) {
        return mvc.patch().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    private static String id(MvcTestResult result) {
        return JsonPath.read(content(result), "$.id");
    }

    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
