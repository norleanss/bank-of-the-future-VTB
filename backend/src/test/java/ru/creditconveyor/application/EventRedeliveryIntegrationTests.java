package ru.creditconveyor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.creditconveyor.application.ApplicationStatus.DISBURSED;
import static ru.creditconveyor.application.ApplicationStatus.ENRICHMENT;
import static ru.creditconveyor.application.ApplicationStatus.SCORING;
import static ru.creditconveyor.application.ApplicationStatus.SUBMITTED;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import ru.creditconveyor.IntegrationTest;
import ru.creditconveyor.enrichment.CompanyData;
import ru.creditconveyor.enrichment.CompanyDataCollected;

/**
 * Событие конвейера может прийти повторно (журнал событий доставляет недоставленное после перезапуска) или от
 * прежнего круга обработки. Ни то, ни другое не должно дать второго решения, второй выдачи или лишней истории.
 */
class EventRedeliveryIntegrationTests extends IntegrationTest {

    private static final UUID CLIENT_ID = UUID.fromString("7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f");

    /** Данные «Северного ветра», как их собирает обогащение из моков. */
    private static final CompanyData SEVERNY_VETER_DATA = new CompanyData(true, List.of(), "ACTIVE",
            LocalDate.of(2021, 5, 14), new BigDecimal("0.00"), 1, new BigDecimal("600000.00"), 0);

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ApplicationStateMachine stateMachine;

    @Autowired
    LoanApplicationRepository applications;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void redeliveredEventsDoNotProcessFinishedApplicationAgain() {
        UUID id = create("""
                {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "500000.00",
                 "term": 24, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM",
                 "financials": {"annualRevenue": "20000000.00", "annualExpenses": "14000000.00",
                                "monthlyDebtPayment": "50000.00"}}""");
        post("/applications/" + id + "/submit", null);
        await().atMost(Duration.ofSeconds(15)).until(() -> stateMachine.statusOf(id) == ApplicationStatus.APPROVED);
        post("/applications/" + id + "/offer/accept", null);
        String code = JsonPath.read(content(post("/applications/" + id + "/signing/request-code", null)), "$.demoCode");
        post("/applications/" + id + "/signing/confirm", "{\"code\": \"%s\"}".formatted(code));
        await().atMost(Duration.ofSeconds(15)).until(() -> stateMachine.statusOf(id) == DISBURSED);
        UUID enrichmentId = jdbc.queryForObject("SELECT id FROM enrichment.enrichments WHERE application_id = ?",
                UUID.class, id);
        int historyBefore = historySize(id);

        publish(new EnrichmentRequested(id, UUID.fromString(SEVERNY_VETER), 1));
        publish(new CompanyDataCollected(id, enrichmentId, 1, SEVERNY_VETER_DATA));
        publish(new ContractSigned(id));
        awaitDelivered(id);

        assertThat(stateMachine.statusOf(id)).isEqualTo(DISBURSED);
        assertThat(historySize(id)).isEqualTo(historyBefore);
        assertThat(count("enrichment.enrichments", id)).isEqualTo(1);
        assertThat(count("scoring.decisions", id)).isEqualTo(1);
        assertThat(count("scoring.scoring_results", id)).isEqualTo(1);
    }

    /** Заявка снова на проверке (круг 2) — данные и запрос первого круга, доставленные поздно, не используются. */
    @Test
    void staleRoundEventIsIgnored() {
        UUID id = create("""
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "800000.00",
                 "term": 60, "termUnit": "DAYS", "fundingMode": "ON_DEMAND"}""");
        // В обход конвейера: круг 1 закончился запросом сведений, клиент ответил — круг 2
        transactions.executeWithoutResult(status -> {
            LoanApplication application = applications.findById(id).orElseThrow();
            application.changeStatus(SUBMITTED, Actor.client(CLIENT_ID), null, clock.instant());
            applications.save(application);
        });
        assertThat(stateMachine.beginEnrichment(id, 1)).isTrue();
        stateMachine.transition(id, SCORING, Actor.system(), null);
        stateMachine.requestInfo(id, "Дозаполните анкету", List.of("financials.annualRevenue"), Actor.system(), null,
                List.of());
        transactions.executeWithoutResult(status -> {
            LoanApplication application = applications.findById(id).orElseThrow();
            application.answerInfoRequest("Готово", Actor.client(CLIENT_ID), clock.instant(), false);
            applications.save(application);
        });
        int historyBefore = historySize(id);

        assertThat(stateMachine.beginEnrichment(id, 1)).isFalse();
        publish(new EnrichmentRequested(id, UUID.fromString(SEVERNY_VETER), 1));
        publish(new CompanyDataCollected(id, UUID.randomUUID(), 1, SEVERNY_VETER_DATA));
        awaitDelivered(id);

        assertThat(stateMachine.statusOf(id)).isEqualTo(ENRICHMENT);
        assertThat(historySize(id)).isEqualTo(historyBefore);
        assertThat(count("enrichment.enrichments", id)).isZero();
        assertThat(count("scoring.decisions", id)).isZero();

        // Данные текущего круга принимаются
        publish(new CompanyDataCollected(id, UUID.randomUUID(), 2, SEVERNY_VETER_DATA));
        awaitDelivered(id);
        assertThat(count("scoring.decisions", id)).isEqualTo(1);
        assertThat(stateMachine.statusOf(id)).isNotEqualTo(ENRICHMENT);
    }

    // --- Сбой обработки ---

    @Test
    void processingFailureInSubmittedSendsToManualReview() {
        UUID id = submittedWithoutProcessing();

        assertThat(stateMachine.failProcessing(id, 1)).isTrue();

        assertThat(stateMachine.statusOf(id)).isEqualTo(ApplicationStatus.MANUAL_REVIEW);
        var card = assertThat(mvc.get().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatusOk().bodyJson();
        card.extractingPath("$.reasons[*].code").asArray().containsExactly("PROCESSING_ERROR");
        card.extractingPath("$.availableActions").asArray().containsExactly("CANCEL");
        var history = assertThat(mvc.get().uri("/applications/" + id + "/history")
                .header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))).bodyJson();
        history.extractingPath("$[*].toStatus").asArray()
                .containsExactly("DRAFT", "SUBMITTED", "ENRICHMENT", "MANUAL_REVIEW");
        history.extractingPath("$[2].actorType").isEqualTo("SYSTEM");
        history.extractingPath("$[3].comment")
                .isEqualTo("Автоматическая обработка не завершилась — заявку проверит специалист");
    }

    @Test
    void processingFailureDoesNotTouchFinishedApplication() {
        UUID id = submittedWithoutProcessing();
        stateMachine.beginEnrichment(id, 1);
        stateMachine.transition(id, SCORING, Actor.system(), null);
        stateMachine.offer(id, ApplicationStatus.APPROVED, StatusTransitionsTest.terms(false), Actor.system(), null);
        int historyBefore = historySize(id);

        assertThat(stateMachine.failProcessing(id, 1)).isFalse();

        assertThat(stateMachine.statusOf(id)).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(historySize(id)).isEqualTo(historyBefore);
    }

    /** Ошибка в запоздавшем событии прежнего круга не срывает идущую проверку. */
    @Test
    void processingFailureOfAnotherRoundIsIgnored() {
        UUID id = submittedWithoutProcessing();
        stateMachine.beginEnrichment(id, 1);
        int historyBefore = historySize(id);

        assertThat(stateMachine.failProcessing(id, 0)).isFalse();
        assertThat(stateMachine.failProcessing(id, 2)).isFalse();

        assertThat(stateMachine.statusOf(id)).isEqualTo(ENRICHMENT);
        assertThat(historySize(id)).isEqualTo(historyBefore);
    }

    private UUID submittedWithoutProcessing() {
        UUID id = create("""
                {"companyId": "%s", "productCode": "OVERDRAFT", "purpose": "CASH_GAP", "requestedAmount": "800000.00",
                 "term": 60, "termUnit": "DAYS", "fundingMode": "ON_DEMAND"}""");
        transactions.executeWithoutResult(status -> {
            LoanApplication application = applications.findById(id).orElseThrow();
            application.changeStatus(SUBMITTED, Actor.client(CLIENT_ID), null, clock.instant());
            applications.save(application);
        });
        return id;
    }

    private void publish(Object event) {
        transactions.executeWithoutResult(status -> events.publishEvent(event));
    }

    /** Доставленные события удаляются из журнала. */
    private void awaitDelivered(UUID id) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM events.event_publication WHERE serialized_event LIKE '%' || ? || '%'",
                Integer.class, id.toString()) == 0);
    }

    private int historySize(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM application.status_history WHERE application_id = ?",
                Integer.class, id);
    }

    private int count(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE application_id = ?", Integer.class, id);
    }

    private UUID create(String draft) {
        return UUID.fromString(JsonPath.read(content(post("/applications", draft.formatted(SEVERNY_VETER))), "$.id"));
    }

    private MvcTestResult post(String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
