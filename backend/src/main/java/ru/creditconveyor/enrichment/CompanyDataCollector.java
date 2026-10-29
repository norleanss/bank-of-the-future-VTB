package ru.creditconveyor.enrichment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ru.creditconveyor.application.ApplicationStateMachine;
import ru.creditconveyor.application.EnrichmentRequested;
import ru.creditconveyor.identity.CompanyDirectory;

/**
 * Собирает данные компании по заявке. Запросы к источникам идут вне транзакции: соединение с базой не держится,
 * пока ждём ответа. ФНС и БКИ опрашиваются параллельно; если ЕГРЮЛ не знает компанию, их не спрашиваем.
 */
@Component
class CompanyDataCollector {

    private static final Logger log = LoggerFactory.getLogger(CompanyDataCollector.class);

    private final ApplicationStateMachine stateMachine;
    private final CompanyDirectory companies;
    private final SourceClient sources;
    private final EnrichmentRepository enrichments;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    CompanyDataCollector(ApplicationStateMachine stateMachine, CompanyDirectory companies, SourceClient sources,
                         EnrichmentRepository enrichments, ApplicationEventPublisher events, JsonMapper jsonMapper,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.stateMachine = stateMachine;
        this.companies = companies;
        this.sources = sources;
        this.enrichments = enrichments;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Без {@code @Transactional}: журнал событий отметит доставку после выхода из метода. Непредвиденная ошибка —
     * заявка на ручную проверку; если не удалось и это, событие останется недоставленным до перезапуска.
     */
    @Async
    @TransactionalEventListener
    void on(EnrichmentRequested event) {
        try {
            collect(event);
        } catch (OptimisticLockingFailureException e) {
            // Заявку одновременно изменил другой обработчик (например, то же событие доставлено дважды) — не сбой
            log.info("Заявка {} изменилась во время сбора данных, статус {} — пропускаем", event.applicationId(),
                    stateMachine.statusOf(event.applicationId()));
        } catch (RuntimeException e) {
            log.error("Заявка {}: сбой сбора данных источников, передаём на ручную проверку", event.applicationId(), e);
            stateMachine.failProcessing(event.applicationId(), event.round());
        }
    }

    private void collect(EnrichmentRequested event) {
        UUID applicationId = event.applicationId();
        if (!stateMachine.beginEnrichment(applicationId, event.round())) {
            log.info("Заявка {} уже не ждёт проверки по источникам (круг {}) — пропускаем", applicationId, event.round());
            return;
        }
        String inn = companies.innOf(event.companyId()).orElse(null);
        Enrichment enrichment = new Enrichment(applicationId, inn, clock.instant());
        if (inn == null) {
            enrichment.companyNotFound();
        } else {
            collect(enrichment, inn);
        }
        transaction.executeWithoutResult(status -> {
            enrichments.save(enrichment);
            events.publishEvent(new CompanyDataCollected(applicationId, enrichment.getId(), event.round(),
                    enrichment.toCompanyData()));
        });
    }

    private void collect(Enrichment enrichment, String inn) {
        SourceClient.Response egrul = sources.fetch(Source.EGRUL, inn);
        if (egrul.status() == SourceStatus.NOT_FOUND) {
            enrichment.companyNotFound();
            enrichment.add(new SourceResult(egrul, SourceStatus.NOT_FOUND, json(egrul.body()), null));
            return;
        }
        SourceClient.Response fns;
        SourceClient.Response bki;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<SourceClient.Response> fnsCall = executor.submit(() -> sources.fetch(Source.FNS, inn));
            Future<SourceClient.Response> bkiCall = executor.submit(() -> sources.fetch(Source.BKI, inn));
            fns = fnsCall.get();
            bki = bkiCall.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Сбор данных по заявке прерван", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Ошибка запроса к источнику", e.getCause());
        }
        apply(enrichment, egrul, node -> enrichment.registry(
                text(node, "name"), text(node, "ogrn"), text(node, "businessType"), text(node, "status"),
                LocalDate.parse(text(node, "registrationDate")), node.path("okved").asString(null),
                node.path("region").asString(null)));
        apply(enrichment, fns, node -> enrichment.taxes(new BigDecimal(text(node, "taxDebtAmount"))));
        apply(enrichment, bki, node -> enrichment.creditHistory(
                number(node, "activeCreditsCount"), new BigDecimal(text(node, "totalDebtAmount")),
                number(node, "maxOverdueDays12m")));
    }

    /** Ответ, который не удалось разобрать, — такой же сбой источника, как отсутствие ответа. */
    private void apply(Enrichment enrichment, SourceClient.Response response, Consumer<JsonNode> reader) {
        if (response.status() != SourceStatus.OK) {
            enrichment.add(new SourceResult(response, response.status(), json(response.body()), response.error()));
            return;
        }
        try {
            JsonNode node = jsonMapper.readTree(response.body());
            reader.accept(node);
            enrichment.add(new SourceResult(response, SourceStatus.OK, node.toString(), null));
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException e) {
            log.warn("Источник {} вернул ответ, который не удалось разобрать: {}", response.source(), e.getMessage());
            enrichment.add(new SourceResult(response, SourceStatus.ERROR, json(response.body()),
                    "Некорректный ответ источника"));
        }
    }

    /** В jsonb кладём только корректный JSON. */
    private String json(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readTree(body).toString();
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Нет поля " + field);
        }
        return value.asString();
    }

    private static int number(JsonNode node, String field) {
        return Integer.parseInt(text(node, field));
    }
}
