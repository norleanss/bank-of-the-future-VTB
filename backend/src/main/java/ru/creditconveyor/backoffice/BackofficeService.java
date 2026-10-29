package ru.creditconveyor.backoffice;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.BackofficeApplicationDetailDto;
import ru.creditconveyor.api.BackofficeApplicationSummaryPageDto;
import ru.creditconveyor.api.CreateInfoRequestDto;
import ru.creditconveyor.api.DocumentTypeDto;
import ru.creditconveyor.api.InfoRequestDto;
import ru.creditconveyor.api.ManualDecisionRequestDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.application.Actor;
import ru.creditconveyor.application.ApplicationDocuments;
import ru.creditconveyor.application.ApplicationForDecision;
import ru.creditconveyor.application.ApplicationLookup;
import ru.creditconveyor.application.ApplicationRegistry;
import ru.creditconveyor.application.ApplicationStateMachine;
import ru.creditconveyor.application.ApplicationStatus;
import ru.creditconveyor.application.DecisionReason;
import ru.creditconveyor.application.InvalidApplicationException;
import ru.creditconveyor.application.InvalidStatusTransitionException;
import ru.creditconveyor.application.OfferTerms;
import ru.creditconveyor.enrichment.EnrichmentReports;
import ru.creditconveyor.identity.CompanyDirectory;
import ru.creditconveyor.identity.CompanyProfile;
import ru.creditconveyor.identity.CurrentUserProvider;
import ru.creditconveyor.product.ProductConditions;
import ru.creditconveyor.product.ReasonCodes;
import ru.creditconveyor.scoring.DecisionJournal;
import ru.creditconveyor.scoring.EmployeeDecision;
import ru.creditconveyor.scoring.OfferPricing;
import ru.creditconveyor.scoring.ScoringReports;

/**
 * Очереди, карточка и решения андеррайтера. Статус меняет только машина состояний; решение и его запись в журнал
 * решений — в одной транзакции. Условия сотрудника проверяются по условиям продукта, лимит по финансам — нет:
 * его превышение — осознанное решение человека.
 */
@Service
class BackofficeService {

    static final String MANUAL_REJECT = "MANUAL_REJECT";
    static final String MANUAL_COUNTER_OFFER = "MANUAL_COUNTER_OFFER";
    private static final String REJECT_ADVICE = "Подайте новую заявку, когда причина отказа будет устранена";
    private static final String COUNTER_OFFER_ADVICE =
            "Примите предложение или откажитесь от него и подайте новую заявку на других условиях";

    private static final Set<ApplicationStatus> DECIDABLE =
            EnumSet.of(ApplicationStatus.MANUAL_REVIEW, ApplicationStatus.PENDING_CONFIRMATION);
    /** Поля, которые клиент может изменить после отправки заявки (как в {@code PATCH} в {@code NEED_INFO}). */
    private static final List<String> REQUESTABLE_FIELDS = List.of("purpose", "fundingMode", "repaymentType",
            "financials.annualRevenue", "financials.annualExpenses", "financials.bankMonthlyTurnover",
            "financials.monthlyDebtPayment");
    /** Поля ошибок проверки продукта → поля запроса сотрудника. */
    private static final Map<String, String> CONDITION_FIELDS =
            Map.of("productCode", "productCode", "requestedAmount", "amount", "term", "term");

    private final ApplicationRegistry registry;
    private final ApplicationDocuments documents;
    private final ApplicationLookup lookup;
    private final ApplicationStateMachine stateMachine;
    private final CompanyDirectory companies;
    private final EnrichmentReports enrichments;
    private final ScoringReports scorings;
    private final OfferPricing pricing;
    private final DecisionJournal journal;
    private final ProductConditions productConditions;
    private final CurrentUserProvider currentUserProvider;
    private final BackofficeProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    BackofficeService(ApplicationRegistry registry, ApplicationDocuments documents, ApplicationLookup lookup, ApplicationStateMachine stateMachine,
                      CompanyDirectory companies, EnrichmentReports enrichments, ScoringReports scorings,
                      OfferPricing pricing, DecisionJournal journal, ProductConditions productConditions,
                      CurrentUserProvider currentUserProvider, BackofficeProperties properties,
                      PlatformTransactionManager transactionManager, Clock clock) {
        this.registry = registry;
        this.documents = documents;
        this.lookup = lookup;
        this.stateMachine = stateMachine;
        this.companies = companies;
        this.enrichments = enrichments;
        this.scorings = scorings;
        this.pricing = pricing;
        this.journal = journal;
        this.productConditions = productConditions;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    BackofficeApplicationSummaryPageDto list(String queue, ApplicationStatusDto statusFilter, int page, int size) {
        Set<ApplicationStatus> statuses = switch (queue) {
            case "REVIEW" -> EnumSet.of(ApplicationStatus.MANUAL_REVIEW);
            case "CONFIRMATION" -> EnumSet.of(ApplicationStatus.PENDING_CONFIRMATION);
            case "ALL" -> EnumSet.complementOf(EnumSet.of(ApplicationStatus.DRAFT));
            default -> throw new InvalidApplicationException("Неизвестная очередь",
                    Map.of("queue", "Допустимые значения: REVIEW, CONFIRMATION, ALL"));
        };
        if (statusFilter != null) {
            statuses.retainAll(EnumSet.of(ApplicationStatus.valueOf(statusFilter.getValue())));
        }
        if (statuses.isEmpty()) {
            return new BackofficeApplicationSummaryPageDto(page, size, 0, 0, List.of());
        }
        Page<ApplicationRegistry.QueueEntry> result = queue.equals("ALL")
                ? registry.submitted(statuses, page, size)
                : registry.waiting(statuses, page, size);
        Map<UUID, CompanyProfile> profiles = result.getContent().stream()
                .map(ApplicationRegistry.QueueEntry::companyId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::company));
        Map<UUID, ScoringReports.Score> scores = scorings.latestScores(
                result.getContent().stream().map(ApplicationRegistry.QueueEntry::id).toList());
        return new BackofficeApplicationSummaryPageDto(
                result.getNumber(),
                result.getSize(),
                Math.toIntExact(result.getTotalElements()),
                result.getTotalPages(),
                result.getContent().stream()
                        .map(entry -> BackofficeMapper.toSummaryDto(entry, profiles.get(entry.companyId()),
                                scores.get(entry.id()), clock.getZone()))
                        .toList());
    }

    BackofficeApplicationDetailDto card(UUID id) {
        ApplicationRegistry.Card card = registry.card(id).orElseThrow(BackofficeService::notFound);
        return new BackofficeApplicationDetailDto(
                        card.application(),
                        company(card.application().getCompanyId()).toDto(),
                        card.infoRequests(),
                        documents.list(id),
                        card.history())
                .enrichment(enrichments.latest(id).orElse(null))
                .scoring(scorings.latest(id).orElse(null));
    }

    ApplicationDocuments.File document(UUID id, UUID documentId) {
        return documents.file(id, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден"));
    }

    ApplicationDto decide(UUID id, ManualDecisionRequestDto request) {
        Actor employee = currentEmployee();
        transaction.executeWithoutResult(status -> {
            ApplicationForDecision application = decidable(id);
            reasonCodes(request);
            switch (request.getDecision()) {
                case APPROVE -> {
                    if (application.status() == ApplicationStatus.PENDING_CONFIRMATION) {
                        confirm(application, request, employee);
                    } else {
                        approve(application, request, employee);
                    }
                }
                case COUNTER_OFFER -> counterOffer(application, request, employee);
                case REJECT -> reject(application, request, employee);
            }
        });
        return registry.card(id).orElseThrow(BackofficeService::notFound).application();
    }

    InfoRequestDto requestInfo(UUID id, CreateInfoRequestDto request) {
        Actor employee = currentEmployee();
        transaction.executeWithoutResult(status -> {
            ApplicationStatus current = lookup.statusOf(id).orElseThrow(BackofficeService::notFound);
            if (current != ApplicationStatus.MANUAL_REVIEW) {
                throw InvalidStatusTransitionException.action("запросить сведения", current);
            }
            Map<String, String> errors = new LinkedHashMap<>();
            if (request.getMessage().isBlank()) {
                errors.put("message", "Напишите, какие сведения нужны");
            }
            List<String> fields = request.getRequestedFields() != null ? request.getRequestedFields() : List.of();
            for (int i = 0; i < fields.size(); i++) {
                if (!REQUESTABLE_FIELDS.contains(fields.get(i))) {
                    errors.put("requestedFields[" + i + "]",
                            "Можно запросить только поля: " + String.join(", ", REQUESTABLE_FIELDS));
                }
            }
            if (!errors.isEmpty()) {
                throw new InvalidApplicationException("Запрос сведений заполнен неверно", errors);
            }
            List<String> documents = request.getRequestedDocuments() != null
                    ? request.getRequestedDocuments().stream().map(DocumentTypeDto::getValue).distinct().toList()
                    : List.of();
            List<String> requestedFields = fields.stream().distinct().toList();
            String message = request.getMessage().strip();
            stateMachine.requestInfo(id, message, requestedFields, documents, employee,
                    "Запрос сведений: " + message, List.of());
            journal.record(id, new EmployeeDecision(EmployeeDecision.Outcome.NEED_INFO, employee.id(), null,
                    List.of(), requestedFields, message));
        });
        return registry.card(id).orElseThrow(BackofficeService::notFound).infoRequests().getLast();
    }

    /** Одобрение из ручной проверки: по умолчанию условия заявки, ставка каталога. */
    private void approve(ApplicationForDecision application, ManualDecisionRequestDto request, Actor employee) {
        if (request.getProductCode() != null && !request.getProductCode().getValue().equals(application.productCode())) {
            throw new InvalidApplicationException("Одобрить можно только продукт заявки",
                    Map.of("productCode", "Другой продукт — через контрпредложение (COUNTER_OFFER)"));
        }
        OfferTerms terms = terms(application, request);
        String comment = request.getComment() != null ? request.getComment() : "Одобрено специалистом банка";
        stateMachine.offer(application.id(), ApplicationStatus.APPROVED, terms, employee, comment, List.of());
        journal.record(application.id(), new EmployeeDecision(EmployeeDecision.Outcome.APPROVE, employee.id(),
                terms.amount(), reasonCodes(request), List.of(), request.getComment()));
    }

    /** Подтверждение предварительного одобрения: клиент уже принял условия, их не меняем. */
    private void confirm(ApplicationForDecision application, ManualDecisionRequestDto request, Actor employee) {
        Map<String, String> errors = new LinkedHashMap<>();
        String hint = "Условия уже приняты клиентом — другие условия через контрпредложение (COUNTER_OFFER)";
        if (request.getProductCode() != null) {
            errors.put("productCode", hint);
        }
        if (request.getAmount() != null) {
            errors.put("amount", hint);
        }
        if (request.getTerm() != null || request.getTermUnit() != null) {
            errors.put(request.getTerm() != null ? "term" : "termUnit", hint);
        }
        if (request.getRate() != null) {
            errors.put("rate", hint);
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Подтверждение не меняет условия", errors);
        }
        stateMachine.transition(application.id(), ApplicationStatus.OFFER_ACCEPTED, employee,
                request.getComment() != null ? request.getComment() : "Условия подтверждены специалистом банка");
        journal.record(application.id(), new EmployeeDecision(EmployeeDecision.Outcome.APPROVE, employee.id(),
                null, reasonCodes(request), List.of(), request.getComment()));
    }

    private void counterOffer(ApplicationForDecision application, ManualDecisionRequestDto request, Actor employee) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.getAmount() == null) {
            errors.put("amount", "Укажите сумму контрпредложения");
        }
        requireComment(request, errors);
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Контрпредложение заполнено неверно", errors);
        }
        OfferTerms terms = terms(application, request);
        String comment = request.getComment().strip();
        stateMachine.offer(application.id(), ApplicationStatus.COUNTER_OFFER, terms, employee, comment,
                List.of(new DecisionReason(MANUAL_COUNTER_OFFER, comment, COUNTER_OFFER_ADVICE)));
        journal.record(application.id(), new EmployeeDecision(EmployeeDecision.Outcome.COUNTER_OFFER,
                employee.id(), terms.amount(), reasonCodes(request), List.of(), comment));
    }

    /** Клиент видит комментарий сотрудника как причину отказа. */
    private void reject(ApplicationForDecision application, ManualDecisionRequestDto request, Actor employee) {
        Map<String, String> errors = new LinkedHashMap<>();
        requireComment(request, errors);
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Укажите причину отказа", errors);
        }
        String comment = request.getComment().strip();
        stateMachine.transition(application.id(), ApplicationStatus.REJECTED, employee, comment,
                List.of(new DecisionReason(MANUAL_REJECT, comment, REJECT_ADVICE)));
        journal.record(application.id(), new EmployeeDecision(EmployeeDecision.Outcome.REJECT, employee.id(),
                null, reasonCodes(request), List.of(), comment));
    }

    /**
     * Условия сотрудника, недостающие — из заявки. Проверка по продукту — та же, что при отправке заявки, с подпиской
     * заявки; ставка — не ниже каталога.
     */
    private OfferTerms terms(ApplicationForDecision application, ManualDecisionRequestDto request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if ((request.getTerm() == null) != (request.getTermUnit() == null)) {
            errors.put(request.getTerm() != null ? "termUnit" : "term", "Срок и единица срока передаются вместе");
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Условия заполнены неверно", errors);
        }
        String productCode = request.getProductCode() != null
                ? request.getProductCode().getValue()
                : application.productCode();
        BigDecimal amount = request.getAmount() != null ? new BigDecimal(request.getAmount()) : application.requestedAmount();
        int term = request.getTerm() != null ? request.getTerm() : application.term();
        var termUnit = request.getTermUnit() != null ? request.getTermUnit() : application.termUnit();

        int businessAgeMonths = company(application.companyId()).businessAgeMonths();
        productConditions.check(productCode, amount, term, termUnit, application.subscription(), businessAgeMonths)
                .forEach(violation -> errors.putIfAbsent(CONDITION_FIELDS.get(violation.field()), violation.message()));
        OfferTerms catalogTerms = pricing.price(productCode, amount, term, termUnit, application.repaymentType(),
                application.subscription(), null).orElse(null);
        if (request.getRate() != null && catalogTerms != null) {
            rateError(request.getRate(), catalogTerms).ifPresent(error -> errors.put("rate", error));
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Условия не подходят под условия продукта", errors);
        }
        return pricing.price(productCode, amount, term, termUnit, application.repaymentType(),
                        application.subscription(), request.getRate())
                .orElseThrow(() -> new InvalidApplicationException("Условия не подходят под условия продукта",
                        Map.of("productCode", "Продукт недоступен")));
    }

    /** Не ниже ставки каталога, не выше {@code app.backoffice.max-rate} для единицы ставки, до сотых. */
    private Optional<String> rateError(BigDecimal rate, OfferTerms catalog) {
        BigDecimal max = properties.maxRate().get(catalog.rateUnit());
        if (rate.stripTrailingZeros().scale() > 2) {
            return Optional.of("Ставка — не больше двух знаков после запятой");
        }
        if (rate.compareTo(catalog.rate()) < 0) {
            return Optional.of("Ставка не может быть ниже ставки каталога — " + plain(catalog.rate()));
        }
        if (rate.compareTo(max) > 0) {
            return Optional.of("Ставка не может быть выше " + plain(max)
                    + (catalog.rateUnit() == RateUnitDto.PERCENT_PER_DAY ? "% в день" : "% годовых"));
        }
        return Optional.empty();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private ApplicationForDecision decidable(UUID id) {
        ApplicationStatus status = lookup.statusOf(id).orElseThrow(BackofficeService::notFound);
        if (!DECIDABLE.contains(status)) {
            throw InvalidStatusTransitionException.action("принять решение по заявке", status);
        }
        return lookup.forDecision(id).orElseThrow(() -> new IllegalStateException(
                "У отправленной заявки " + id + " нет условий"));
    }

    private static void requireComment(ManualDecisionRequestDto request, Map<String, String> errors) {
        if (request.getComment() == null || request.getComment().isBlank()) {
            errors.put("comment", "Комментарий обязателен — клиент увидит его в причине решения");
        }
    }

    /** Только коды из справочника {@code reasonCodes}: они идут в журнал решений для учёта. */
    private static List<String> reasonCodes(ManualDecisionRequestDto request) {
        List<String> codes = request.getReasonCodes() != null ? request.getReasonCodes() : List.of();
        Map<String, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < codes.size(); i++) {
            if (!ReasonCodes.isKnown(codes.get(i))) {
                errors.put("reasonCodes[" + i + "]", "Неизвестный код причины — коды в справочнике reasonCodes");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Решение заполнено неверно", errors);
        }
        return codes.stream().distinct().toList();
    }

    private CompanyProfile company(UUID companyId) {
        return companies.profile(companyId)
                .orElseThrow(() -> new IllegalStateException("Компания " + companyId + " не найдена"));
    }

    private Actor currentEmployee() {
        return Actor.employee(currentUserProvider.currentUser().id());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Заявка не найдена");
    }
}
