package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import ru.creditconveyor.application.ActorType;
import ru.creditconveyor.application.ApplicationStatus;
import ru.creditconveyor.application.ApplicationStatusChanged;

/**
 * Уведомления клиенту: создаются по событию смены статуса — при решении системы, решении сотрудника и истечении
 * предложения фоновой задачей; список, отметка о прочтении, счётчик в списке заявок и поток SSE.
 */
class NotificationIntegrationTests extends IntegrationTest {

    private static final Set<String> IN_PROGRESS = Set.of("SUBMITTED", "ENRICHMENT", "SCORING");
    private static final String CLIENT_ID = "7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f";

    private static final String SEVERNY_VETER_LOAN = """
            {"companyId": "%s", "productCode": "LUMP_SUM_LOAN", "purpose": "EQUIPMENT", "requestedAmount": "500000.00",
             "term": 24, "termUnit": "MONTHS", "fundingMode": "LUMP_SUM", "repaymentType": "ANNUITY",
             "financials": {"annualRevenue": "20000000.00", "annualExpenses": "14000000.00",
                            "monthlyDebtPayment": "50000.00"}}
            """.formatted(SEVERNY_VETER);
    private static final String MOROZOV_LINE = """
            {"companyId": "%s", "productCode": "CREDIT_LINE", "purpose": "WORKING_CAPITAL", "requestedAmount": "1000000.00",
             "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES",
             "financials": {"annualRevenue": "12000000.00", "annualExpenses": "9000000.00", "monthlyDebtPayment": "50000.00"}}
            """.formatted(MOROZOV);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    private final UUID historyApplicationId = UUID.randomUUID();

    /** Общая база может содержать больше одной страницы; проверяем этот случай в каждом сценарии. */
    @BeforeEach
    void fillFirstNotificationPage() {
        jdbc.update("""
                INSERT INTO notification.notifications
                    (id, event_id, recipient_id, application_id, type, title, body, created_at)
                SELECT gen_random_uuid(), gen_random_uuid(), ?::uuid, ?::uuid,
                       'APPLICATION_SUBMITTED', 'Другая заявка', 'Уведомление из истории', ?::timestamptz
                FROM generate_series(1, 101)
                """, CLIENT_ID, historyApplicationId.toString(), clock.instant().plusSeconds(60).toString());
    }

    @AfterEach
    void removeNotificationHistory() {
        jdbc.update("DELETE FROM notification.notifications WHERE application_id = ?", historyApplicationId);
    }

    @Test
    void systemDecisionNotifiesClient() {
        String id = approved();

        awaitNotifications(id, 2);
        var notifications = allPages(CLIENT, "/notifications");
        String ours = "$[?(@.applicationId == '%s')]".formatted(id);
        // О внутренних шагах конвейера уведомлений нет. Порядок здесь не проверяем: у тестовых часов одно время
        assertThat(notifications.<List<String>>read(ours + ".type"))
                .containsExactlyInAnyOrder("DECISION_MADE", "APPLICATION_SUBMITTED");
        assertThat(notifications.<List<String>>read(ours + ".title"))
                .containsExactlyInAnyOrder("Заявка одобрена", "Заявка отправлена");
        assertThat(notifications.<List<String>>read(ours + ".body")).contains(
                "Одобрено 500 000 ₽ на 24 месяца. Примите предложение до 08.11.2026.");
        assertThat(notifications.<List<Boolean>>read(ours + ".read")).containsOnly(false);

        // Счётчик в списке заявок; прочитанное из него уходит
        assertThat(unread(id)).isEqualTo(2);
        String notificationId = firstNotificationId(id);
        assertThat(post(CLIENT, "/notifications/" + notificationId + "/read", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(post(CLIENT, "/notifications/" + notificationId + "/read", null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(unread(id)).isEqualTo(1);
        List<String> unreadIds = allPages(CLIENT, "/notifications?unreadOnly=true").read("$[*].id");
        assertThat(unreadIds).doesNotContain(notificationId);

        // Своё видит только получатель: чужое — как несуществующее
        assertThat(post(OTHER_CLIENT, "/notifications/" + notificationId + "/read", null)).hasStatus(HttpStatus.NOT_FOUND);
        List<String> foreign = allPages(OTHER_CLIENT, "/notifications").read("$[*].id");
        assertThat(foreign).doesNotContain(notificationId);
        assertThat(post(CLIENT, "/notifications/" + UUID.randomUUID() + "/read", null)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void underwriterDecisionNotifiesClient() {
        String id = submit(MOROZOV_LINE);
        awaitStatus(id, "MANUAL_REVIEW");
        awaitNotifications(id, 2);

        clock.advance(Duration.ofMinutes(5));
        assertThat(post(UNDERWRITER, "/backoffice/applications/" + id + "/decision",
                "{\"decision\": \"REJECT\", \"comment\": \"Просрочка повторяется, обороты падают\"}")).hasStatusOk();

        awaitNotifications(id, 3);
        var notifications = allPages(CLIENT, "/notifications");
        String ours = "$[?(@.applicationId == '%s')]".formatted(id);
        assertThat(notifications.<List<String>>read(ours + ".type"))
                .containsExactlyInAnyOrder("DECISION_MADE", "STATUS_CHANGED", "APPLICATION_SUBMITTED");
        // Решение сотрудника — на 5 минут позже, поэтому первым; отправка и ручная проверка — в одно время
        assertThat(ours(id, "title")).first().isEqualTo("Заявка отклонена");
        assertThat(ours(id, "title")).contains("Заявка на рассмотрении у специалиста", "Заявка отправлена");
        assertThat(ours(id, "body").getFirst())
                .endsWith("банк не может выдать кредит: Просрочка повторяется, обороты падают.");
    }

    /** Истечение — от фоновой задачи, как в работе: запускаем само запланированное задание. */
    @Test
    void offerExpiryByBackgroundJobNotifiesClient() {
        String id = approved();
        awaitNotifications(id, 2);

        clock.advance(Duration.ofDays(8));
        runScheduled("OfferExpiryJob");

        awaitNotifications(id, 3);
        assertThat(ours(id, "type").getFirst()).isEqualTo("OFFER_EXPIRED");
        assertThat(ours(id, "body").getFirst()).contains("действовало до 08.11.2026. Вы можете подать новую заявку");
        assertThat(jdbc.queryForObject("""
                SELECT actor_type FROM application.status_history WHERE application_id = ?::uuid
                ORDER BY id DESC LIMIT 1""", String.class, id)).isEqualTo("SYSTEM");
    }

    @Test
    void streamDeliversNotification() throws Exception {
        MvcResult stream = mockMvc.perform(MockMvcRequestBuilders.get("/notifications/stream")
                        .param("access_token", bearer(CLIENT).substring("Bearer ".length()))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8)).startsWith("event:ping");

        String id = approved();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(stream.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("event:notification")
                        .contains("\"applicationId\":\"" + id + "\"")
                        .contains("\"type\":\"DECISION_MADE\""));
        stream.getRequest().getAsyncContext().complete();
    }

    @Test
    void streamRequiresToken() throws Exception {
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/notifications/stream")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/notifications/stream").param("access_token", "not-a-jwt"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // В остальных эндпоинтах токен параметром не принимается
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/notifications")
                        .param("access_token", bearer(CLIENT).substring("Bearer ".length())))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** Повторная доставка того же события (после перезапуска) второго уведомления не создаёт. */
    @Test
    void redeliveredEventDoesNotDuplicateNotification() {
        UUID eventId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        var event = new ApplicationStatusChanged(eventId, applicationId, "CR-2026-999999", UUID.fromString(SEVERNY_VETER),
                UUID.fromString(CLIENT_ID), ApplicationStatus.SIGNED, ApplicationStatus.DISBURSED, ActorType.SYSTEM,
                clock.instant(), List.of(), null, null);
        transaction.executeWithoutResult(status -> events.publishEvent(event));
        awaitNotifications(applicationId.toString(), 1);
        transaction.executeWithoutResult(status -> events.publishEvent(event));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM events.event_publication WHERE serialized_event LIKE '%' || ? || '%'",
                Integer.class, eventId.toString())).isZero());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.notifications WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    // --- Помощники ---

    private String approved() {
        String id = submit(SEVERNY_VETER_LOAN);
        awaitStatus(id, "APPROVED");
        return id;
    }

    private void awaitNotifications(String applicationId, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification.notifications WHERE application_id = ?::uuid",
                Integer.class, applicationId)).isEqualTo(count));
    }

    private String firstNotificationId(String applicationId) {
        return ours(applicationId, "id").getFirst();
    }

    /** Поле уведомлений клиента по заявке, новые первыми. */
    private List<String> ours(String applicationId, String field) {
        return allPages(CLIENT, "/notifications")
                .read("$[?(@.applicationId == '%s')].%s".formatted(applicationId, field));
    }

    private int unread(String applicationId) {
        List<Integer> counts = allPages(CLIENT, "/applications")
                .read("$[?(@.id == '%s')].unreadNotifications".formatted(applicationId));
        return counts.getFirst();
    }

    /** Другие тесты оставляют свои заявки и уведомления: искомая запись может быть на любой странице. */
    private DocumentContext allPages(String login, String uri) {
        List<Object> content = new ArrayList<>();
        int page = 0;
        int totalPages;
        do {
            String separator = uri.contains("?") ? "&" : "?";
            var result = get(login, uri + separator + "size=100&page=" + page);
            assertThat(result).hasStatusOk();
            var response = JsonPath.parse(body(result));
            content.addAll(response.<List<?>>read("$.content"));
            totalPages = response.read("$.totalPages");
            page++;
        } while (page < totalPages);
        return JsonPath.parse(content);
    }

    /** Запланированная задача по имени класса — то, что запускает планировщик (описание задачи — «класс.метод»). */
    private void runScheduled(String beanClass) {
        scheduledTasks.getScheduledTasks().stream()
                .map(task -> task.getTask().getRunnable())
                .filter(runnable -> runnable.toString().contains("." + beanClass + "."))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Нет запланированной задачи " + beanClass))
                .run();
    }

    private String submit(String draft) {
        String id = read(post(CLIENT, "/applications", draft), "$.id");
        assertThat(post(CLIENT, "/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED);
        return id;
    }

    private void awaitStatus(String id, String status) {
        await().atMost(Duration.ofSeconds(15)).until(() -> !IN_PROGRESS.contains(read(get(CLIENT, "/applications/" + id), "$.status")));
        assertThat(read(get(CLIENT, "/applications/" + id), "$.status")).isEqualTo(status);
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
