package ru.creditconveyor.scoring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.application.Actor;
import ru.creditconveyor.application.ApplicationForDecision;
import ru.creditconveyor.application.ApplicationLookup;
import ru.creditconveyor.application.ApplicationStateMachine;
import ru.creditconveyor.application.ApplicationStatus;
import ru.creditconveyor.application.DecisionReason;
import ru.creditconveyor.application.OfferTerms;
import ru.creditconveyor.enrichment.CompanyData;
import ru.creditconveyor.enrichment.CompanyDataCollected;
import ru.creditconveyor.product.ProductCatalog;
import ru.creditconveyor.product.ProductTerms;

/**
 * Решение по заявке, когда данные источников собраны: политика ({@link CreditPolicy}) решает, результат
 * сохраняется, статус меняется через машину состояний от имени системы — всё в одной транзакции. Непредвиденная
 * ошибка откатывает её, и заявка в отдельной транзакции уходит на ручную проверку.
 */
@Component
class CreditDecisions {

    private static final Logger log = LoggerFactory.getLogger(CreditDecisions.class);

    /** Эти решения принимаются по итогам обогащения, без скоринга: {@code ENRICHMENT → REJECTED / MANUAL_REVIEW}. */
    private static final Set<String> ENRICHMENT_OUTCOMES = Set.of(Reason.COMPANY_NOT_FOUND, Reason.SOURCE_UNAVAILABLE);

    private final ApplicationLookup applications;
    private final ApplicationStateMachine stateMachine;
    private final ProductCatalog catalog;
    private final CreditPolicy policy;
    private final ScoringProperties properties;
    private final ScoringRecordRepository scorings;
    private final DecisionRecordRepository decisions;
    private final TransactionTemplate transaction;
    private final Clock clock;

    CreditDecisions(ApplicationLookup applications, ApplicationStateMachine stateMachine, ProductCatalog catalog,
                    CreditPolicy policy, ScoringProperties properties, ScoringRecordRepository scorings,
                    DecisionRecordRepository decisions, PlatformTransactionManager transactionManager, Clock clock) {
        this.applications = applications;
        this.stateMachine = stateMachine;
        this.catalog = catalog;
        this.policy = policy;
        this.properties = properties;
        this.scorings = scorings;
        this.decisions = decisions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(CompanyDataCollected event) {
        try {
            transaction.executeWithoutResult(status -> decide(event));
        } catch (OptimisticLockingFailureException e) {
            // Заявку одновременно изменил другой обработчик (например, то же событие доставлено дважды) — не сбой
            log.info("Заявка {} изменилась во время принятия решения, статус {} — пропускаем", event.applicationId(),
                    stateMachine.statusOf(event.applicationId()));
        } catch (RuntimeException e) {
            log.error("Заявка {}: сбой при принятии решения, передаём на ручную проверку", event.applicationId(), e);
            stateMachine.failProcessing(event.applicationId(), event.round());
        }
    }

    private void decide(CompanyDataCollected event) {
        UUID id = event.applicationId();
        ApplicationForDecision application = applications.forDecision(id).orElse(null);
        // Повторная доставка события или событие прежнего круга (до ответа на запрос) — решение уже не нужно
        if (application == null || application.status() != ApplicationStatus.ENRICHMENT
                || application.round() != event.round()) {
            log.info("Заявка {} уже не ждёт решения по данным круга {} — пропускаем", id, event.round());
            return;
        }
        ProductTerms product = catalog.terms(application.productCode())
                .orElseThrow(() -> new IllegalStateException("Продукт " + application.productCode() + " недоступен"));
        ProductTerms alternative = catalog.terms(properties.alternativeProduct()).orElse(null);
        Decision decision = policy.decide(creditCase(application, product, alternative, catalog.active(), event.data()),
                LocalDate.now(clock));

        ScoringRecord scoring = decision.scoring() != null
                ? scorings.save(new ScoringRecord(id, decision.scoring(), clock.instant()))
                : null;
        decisions.save(new DecisionRecord(id, event.enrichmentId(), scoring != null ? scoring.getId() : null,
                decision, clock.instant()));
        apply(id, decision);
        log.info("Заявка {}: решение {}, причины {}", id, decision.outcome(),
                decision.reasons().stream().map(Reason::code).toList());
    }

    private void apply(UUID id, Decision decision) {
        Actor system = Actor.system();
        List<DecisionReason> reasons = decision.reasons().stream()
                .map(reason -> new DecisionReason(reason.code(), reason.message(), reason.advice())).toList();
        boolean onEnrichment = decision.reasons().stream().anyMatch(r -> ENRICHMENT_OUTCOMES.contains(r.code()));
        if (!onEnrichment) {
            stateMachine.transition(id, ApplicationStatus.SCORING, system, "Данные источников получены");
        }
        String comment = comment(decision);
        switch (decision.outcome()) {
            case REJECT -> stateMachine.transition(id, ApplicationStatus.REJECTED, system, comment, reasons);
            case MANUAL_REVIEW -> stateMachine.transition(id, ApplicationStatus.MANUAL_REVIEW, system, comment, reasons);
            case NEED_INFO -> stateMachine.requestInfo(id, decision.reasons().getFirst().message(),
                    decision.missingFields(), system, comment, reasons);
            case APPROVE -> stateMachine.offer(id, ApplicationStatus.APPROVED, terms(decision.offer()), system, comment,
                    reasons);
            case COUNTER_OFFER -> stateMachine.offer(id, ApplicationStatus.COUNTER_OFFER, terms(decision.offer()),
                    system, comment, reasons);
        }
    }

    /** Для истории: «Балл 95, грейд A. Автоматическое одобрение» */
    private static String comment(Decision decision) {
        String score = decision.scoring() != null
                ? "Балл %d, грейд %s. ".formatted(decision.scoring().score(), decision.scoring().grade())
                : "";
        String outcome = switch (decision.outcome()) {
            case APPROVE -> decision.offer().preliminary()
                    ? "Предварительное одобрение — после согласия клиента условия подтвердит андеррайтер"
                    : "Автоматическое одобрение";
            case COUNTER_OFFER -> "Контрпредложение на сумму лимита"
                    + (decision.offer().preliminary() ? ", предварительное" : "");
            default -> decision.reasons().stream().map(Reason::message).collect(Collectors.joining("; "));
        };
        return score + outcome;
    }

    private static CreditCase creditCase(ApplicationForDecision application, ProductTerms product,
                                         ProductTerms alternative, List<ProductTerms> catalog, CompanyData data) {
        return new CreditCase(product, alternative, catalog, application.requestedAmount(), application.term(),
                application.termUnit(), application.repaymentType(), application.subscription(),
                new CreditCase.Financials(application.annualRevenue(), application.annualExpenses(),
                        application.bankMonthlyTurnover(), application.monthlyDebtPayment()),
                new CreditCase.ExternalData(data.companyFound(), data.unavailableSources(), data.companyStatus(),
                        data.registrationDate(), data.taxDebtAmount(), data.activeCreditsCount(),
                        data.totalDebtAmount(), data.maxOverdueDays12m()));
    }

    static OfferTerms terms(ProposedOffer offer) {
        return new OfferTerms(ProductCodeDto.fromValue(offer.productCode()), offer.productName(), offer.amount(),
                offer.term(), offer.termUnit(), offer.termKind(), offer.agreementTermMonths(), offer.rate(), offer.rateUnit(),
                offer.monthlyPayment(), offer.subscription(), offer.subscriptionFee(), offer.preliminary());
    }
}
