package ru.creditconveyor.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;

import ru.creditconveyor.api.ApplicationCreateRequestDto;
import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.ApplicationPatchRequestDto;
import ru.creditconveyor.api.ApplicationSummaryPageDto;
import ru.creditconveyor.api.SigningCodeResponseDto;
import ru.creditconveyor.api.StatusHistoryItemDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.identity.ClientCompanies;
import ru.creditconveyor.identity.CompanyAccess;
import ru.creditconveyor.identity.CompanyProfile;
import ru.creditconveyor.identity.CurrentUserProvider;
import ru.creditconveyor.product.ProductConditions;

/**
 * Действия клиента с заявками своих компаний. Доступ и истечение оффера проверяются до транзакции действия:
 * {@code OFFER_EXPIRED} сохраняется, даже если действие завершится ошибкой, и запрос держит одно соединение.
 */
@Service
class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ATTEMPTS_EXHAUSTED = "Попытки закончились — запросите новый код";
    private static final Set<String> LOCKED_AFTER_SUBMIT =
            Set.of("productCode", "requestedAmount", "term", "termUnit", "subscription");

    private final LoanApplicationRepository applications;
    private final CompanyAccess companyAccess;
    private final ClientCompanies clientCompanies;
    private final CurrentUserProvider currentUserProvider;
    private final ProductConditions productConditions;
    private final OfferExpiration offerExpiration;
    private final UnreadNotifications unreadNotifications;
    private final ApplicationDocuments documents;
    private final SigningCodeRepository signingCodes;
    private final SigningProperties signing;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    ApplicationService(LoanApplicationRepository applications, CompanyAccess companyAccess,
                       ClientCompanies clientCompanies, CurrentUserProvider currentUserProvider,
                       ProductConditions productConditions, OfferExpiration offerExpiration,
                       UnreadNotifications unreadNotifications, ApplicationDocuments documents,
                       SigningCodeRepository signingCodes, SigningProperties signing, ApplicationEventPublisher events,
                       Clock clock, PlatformTransactionManager transactionManager) {
        this.applications = applications;
        this.companyAccess = companyAccess;
        this.clientCompanies = clientCompanies;
        this.currentUserProvider = currentUserProvider;
        this.productConditions = productConditions;
        this.offerExpiration = offerExpiration;
        this.unreadNotifications = unreadNotifications;
        this.documents = documents;
        this.signingCodes = signingCodes;
        this.signing = signing;
        this.events = events;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    ApplicationDto create(ApplicationCreateRequestDto request) {
        companyAccess.checkAccess(request.getCompanyId());
        DraftChanges changes = ApplicationMapper.toChanges(request);
        validate(changes);
        Actor applicant = currentClient();
        return writeTransaction.execute(status -> {
            Instant now = clock.instant();
            String number = "CR-%d-%06d".formatted(LocalDate.now(clock).getYear(), applications.nextNumber());
            LoanApplication application = LoanApplication.draft(
                    UUID.randomUUID(), number, request.getCompanyId(), applicant, now);
            application.applyChanges(changes, now);
            applications.save(application);
            return toDto(application);
        });
    }

    ApplicationSummaryPageDto list(UUID companyId, int page, int size) {
        List<UUID> companyIds;
        if (companyId != null) {
            companyAccess.checkAccess(companyId);
            companyIds = List.of(companyId);
        } else {
            companyIds = companyAccess.companyIds();
        }
        if (companyIds.isEmpty()) {
            return new ApplicationSummaryPageDto(page, size, 0, 0, List.of());
        }
        offerExpiration.dueApplications(companyIds).forEach(this::expireQuietly);
        Map<UUID, String> companyNames = clientCompanies.currentClientCompanies().stream()
                .collect(Collectors.toMap(CompanyProfile::id, CompanyProfile::name));
        return readTransaction.execute(status -> {
            Page<LoanApplication> result = applications.findByCompanyIdIn(companyIds,
                    PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
            Map<UUID, Integer> unread = unreadNotifications.countByApplication(
                    currentUserProvider.currentUser().id(),
                    result.getContent().stream().map(LoanApplication::getId).toList());
            return new ApplicationSummaryPageDto(
                    result.getNumber(),
                    result.getSize(),
                    Math.toIntExact(result.getTotalElements()),
                    result.getTotalPages(),
                    result.getContent().stream()
                            .map(application -> ApplicationMapper.toSummaryDto(
                                    application, companyNames.get(application.getCompanyId()),
                                    unread.getOrDefault(application.getId(), 0), clock.getZone()))
                            .toList());
        });
    }

    ApplicationDto get(UUID id) {
        prepare(id);
        return readTransaction.execute(status -> toDto(find(id)));
    }

    ApplicationDto update(UUID id, ApplicationPatchRequestDto request, JsonNode document) {
        prepare(id);
        if (document != null && document.get("companyId") != null) {
            throw new InvalidApplicationException("Некоторые поля заполнены неверно",
                    Map.of("companyId", "Компанию заявки изменить нельзя"));
        }
        DraftChanges changes = ApplicationMapper.toChanges(request, document);
        validate(changes);
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            if (application.getStatus() == ApplicationStatus.NEED_INFO) {
                requireOnlyRequestedFields(application, changes);
            }
            application.applyChanges(changes, clock.instant());
            return toDto(application);
        });
    }

    /** Идемпотентна: повторная отправка возвращает заявку как есть. Решение принимается асинхронно. */
    ApplicationDto submit(UUID id) {
        prepare(id);
        Actor client = currentClient();
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            if (application.getSubmittedAt() != null) {
                return toDto(application);
            }
            if (application.getStatus() != ApplicationStatus.DRAFT) {
                throw InvalidStatusTransitionException.action("отправить заявку", application.getStatus());
            }
            requireProcessable(application);
            application.changeStatus(ApplicationStatus.SUBMITTED, client, null, clock.instant());
            applications.save(application);
            // Событие сохраняется в журнал в этой же транзакции и доставляется после её фиксации
            events.publishEvent(new EnrichmentRequested(application.getId(), application.getCompanyId(),
                    application.getProcessingRound()));
            return toDto(application);
        });
    }

    ApplicationDto cancel(UUID id) {
        prepare(id);
        Actor client = currentClient();
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            if (!StatusTransitions.isAllowed(application.getStatus(), ApplicationStatus.CANCELLED, ActorType.CLIENT)) {
                throw InvalidStatusTransitionException.action("отменить заявку", application.getStatus());
            }
            application.changeStatus(ApplicationStatus.CANCELLED, client, null, clock.instant());
            applications.save(application);
            return toDto(application);
        });
    }

    /** Просроченный оффер уже переведён в {@code OFFER_EXPIRED} в {@link #prepare} — здесь будет 409 offer-expired. */
    ApplicationDto acceptOffer(UUID id) {
        prepare(id);
        Actor client = currentClient();
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            application.acceptOffer(client, clock.instant(), clock.getZone());
            applications.save(application);
            return toDto(application);
        });
    }

    ApplicationDto declineOffer(UUID id, String comment) {
        prepare(id);
        Actor client = currentClient();
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            application.declineOffer(client, comment, clock.instant(), clock.getZone());
            applications.save(application);
            return toDto(application);
        });
    }

    /**
     * Поля из запроса нужно дозаполнить через {@code PATCH} до ответа, запрошенные документы — загрузить и приложить
     * ({@code documentIds}). Ответ на запрос системы возвращает заявку
     * на автоматическую проверку, на запрос андеррайтера — к андеррайтеру. Если после отправки изменились условия
     * продукта (продукт сняли, сумма или срок вне условий), клиент этого исправить не может — поля заблокированы, —
     * поэтому не 400, а ручная проверка с причиной {@code PRODUCT_CONDITIONS_CHANGED}.
     */
    ApplicationDto respondInfoRequest(UUID id, UUID requestId, String comment, List<UUID> documentIds) {
        prepare(id);
        Actor client = currentClient();
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            InfoRequest request = application.openInfoRequest()
                    .filter(open -> application.getStatus() == ApplicationStatus.NEED_INFO)
                    .orElseThrow(() -> InvalidStatusTransitionException.action("ответить на запрос банка",
                            application.getStatus()));
            if (!request.getId().equals(requestId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден");
            }
            Map<String, String> unfilled = new LinkedHashMap<>();
            request.getRequestedFields().stream().filter(field -> !application.isFilled(field))
                    .forEach(field -> unfilled.put(field, "Заполните поле перед ответом на запрос"));
            if (!unfilled.isEmpty()) {
                throw new InvalidApplicationException("Дозаполните анкету — банк запросил эти сведения", unfilled);
            }
            documents.attach(id, request, documentIds);
            Map<String, String> violations = productViolations(application);
            application.answerInfoRequest(comment != null && !comment.isBlank() ? comment : "Клиент дополнил анкету",
                    client, clock.instant(), !violations.isEmpty());
            if (!violations.isEmpty()) {
                application.replaceReasons(List.of(new DecisionReason(ApplicationStateMachine.PRODUCT_CONDITIONS_CHANGED,
                        "Условия продукта изменились после отправки заявки (" + String.join("; ", violations.values())
                                + ") — заявку рассмотрит специалист")));
            }
            applications.save(application);
            if (application.getStatus() == ApplicationStatus.ENRICHMENT) {
                events.publishEvent(new EnrichmentRequested(application.getId(), application.getCompanyId(),
                        application.getProcessingRound()));
            }
            return toDto(application);
        });
    }

    /**
     * Новый код заменяет прежний. В демо код возвращается в ответе, чтобы показать его на экране. Два одновременных
     * запроса упираются в уникальный индекс «один действующий код» — второй получает 409, как при конфликте версий.
     */
    SigningCodeResponseDto requestSigningCode(UUID id) {
        prepare(id);
        try {
            return issueSigningCode(id);
        } catch (DataIntegrityViolationException e) {
            throw new OptimisticLockingFailureException("Код подписания запросили одновременно", e);
        }
    }

    private SigningCodeResponseDto issueSigningCode(UUID id) {
        return writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            if (application.getStatus() != ApplicationStatus.OFFER_ACCEPTED) {
                throw InvalidStatusTransitionException.action("запросить код подписания", application.getStatus());
            }
            signingCodes.findByApplicationIdAndStatus(id, SigningCode.ACTIVE).ifPresent(code -> {
                code.supersede();
                signingCodes.flush();
            });
            String code = "%04d".formatted(RANDOM.nextInt(10_000));
            SigningCode signingCode = signingCodes.saveAndFlush(
                    new SigningCode(id, code, clock.instant(), signing.codeTtl()));
            return new SigningCodeResponseDto(signingCode.getExpiresAt().atZone(clock.getZone()).toOffsetDateTime())
                    .demoCode(code);
        });
    }

    /** Неверный ввод сохраняется до ответа 400: попытки не сбрасываются откатом транзакции. */
    ApplicationDto confirmSigning(UUID id, String code) {
        prepare(id);
        Actor client = currentClient();
        SigningResult result = writeTransaction.execute(status -> {
            LoanApplication application = find(id);
            if (application.getStatus() != ApplicationStatus.OFFER_ACCEPTED) {
                throw InvalidStatusTransitionException.action("подписать договор", application.getStatus());
            }
            SigningCode signingCode = signingCodes.findByApplicationIdAndStatus(id, SigningCode.ACTIVE).orElse(null);
            if (signingCode == null) {
                // Действующего кода нет: последний заблокирован после неверных вводов или код не запрашивали
                return new SigningResult(null, signingCodes.existsByApplicationIdAndStatus(id, SigningCode.BLOCKED)
                        ? ATTEMPTS_EXHAUSTED
                        : "Сначала запросите код подписания");
            }
            return switch (signingCode.check(code, clock.instant(), signing.maxAttempts())) {
                case VALID -> {
                    application.changeStatus(ApplicationStatus.SIGNED, client, "Договор подписан кодом подтверждения",
                            clock.instant());
                    applications.save(application);
                    events.publishEvent(new ContractSigned(id));
                    yield new SigningResult(toDto(application), null);
                }
                case WRONG -> new SigningResult(null,
                        "Неверный код. Осталось попыток: " + (signing.maxAttempts() - signingCode.getAttempts()));
                case BLOCKED -> new SigningResult(null, "Неверный код. " + ATTEMPTS_EXHAUSTED);
                case EXPIRED -> new SigningResult(null, "Срок действия кода истёк — запросите новый код");
            };
        });
        if (result.error() != null) {
            throw new InvalidApplicationException("Договор не подписан", Map.of("code", result.error()));
        }
        return result.application();
    }

    private record SigningResult(ApplicationDto application, String error) {
    }

    List<StatusHistoryItemDto> history(UUID id) {
        prepare(id);
        return readTransaction.execute(status -> find(id).getHistory().stream()
                .map(entry -> ApplicationMapper.toDto(entry, clock.getZone()))
                .toList());
    }

    private void prepare(UUID id) {
        UUID companyId = applications.findCompanyIdById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
        companyAccess.checkAccess(companyId);
        expireQuietly(id);
    }

    /** Гонка с фоновой задачей истечения: результат тот же, ошибку не показываем. */
    private void expireQuietly(UUID id) {
        try {
            offerExpiration.expireIfDue(id);
        } catch (OptimisticLockingFailureException e) {
            log.debug("Заявку {} одновременно изменили при истечении предложения", id);
        }
    }

    /** Проверка перед отправкой: без этого конвейеру не по чему принять решение. */
    private void requireProcessable(LoanApplication application) {
        Map<String, String> missing = new LinkedHashMap<>();
        requireForSubmit(missing, "productCode", application.getProductCode());
        requireForSubmit(missing, "purpose", application.getPurpose());
        requireForSubmit(missing, "requestedAmount", application.getRequestedAmount());
        requireForSubmit(missing, "term", application.getTerm());
        requireForSubmit(missing, "fundingMode", application.getFundingMode());
        if (!missing.isEmpty()) {
            throw new InvalidApplicationException("Заполните анкету перед отправкой", missing);
        }
        Map<String, String> violations = productViolations(application);
        if (!violations.isEmpty()) {
            throw new InvalidApplicationException("Параметры заявки не подходят под условия продукта", violations);
        }
    }

    /** Сумма, срок с учётом подписки и возраст бизнеса — по условиям продукта, как в подборе. */
    private Map<String, String> productViolations(LoanApplication application) {
        int businessAgeMonths = clientCompanies.companyProfile(application.getCompanyId()).businessAgeMonths();
        return productConditions.check(
                        application.getProductCode(), application.getRequestedAmount(), application.getTerm(),
                        TermUnitDto.fromValue(application.getTermUnit()), application.isSubscription(),
                        businessAgeMonths)
                .stream()
                .collect(Collectors.toMap(ProductConditions.Violation::field, ProductConditions.Violation::message,
                        (first, second) -> first, LinkedHashMap::new));
    }

    /**
     * После отправки в ответ на запрос банка меняются только запрошенные поля и финансы: продукт, сумма, срок
     * и подписка — это параметры поданной заявки, их проверили при отправке.
     */
    private static void requireOnlyRequestedFields(LoanApplication application, DraftChanges changes) {
        List<String> requested = application.openInfoRequest().map(InfoRequest::getRequestedFields).orElse(List.of());
        Map<String, String> errors = new LinkedHashMap<>();
        for (String field : application.changedFields(changes)) {
            if (LOCKED_AFTER_SUBMIT.contains(field)) {
                errors.put(field, "Поле нельзя изменить после отправки заявки");
            } else if (!field.startsWith("financials.") && !requested.contains(field)) {
                errors.put(field, "Банк не запрашивал это поле");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Можно изменить только сведения, которые запросил банк", errors);
        }
    }

    private LoanApplication find(UUID id) {
        return applications.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
    }

    private Actor currentClient() {
        return Actor.client(currentUserProvider.currentUser().id());
    }

    private ApplicationDto toDto(LoanApplication application) {
        return ApplicationMapper.toDto(application, clock.getZone());
    }

    private static void validate(DraftChanges changes) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (changes.requestedAmount().value() != null && changes.requestedAmount().value().signum() <= 0) {
            errors.put("requestedAmount", "Сумма должна быть больше нуля");
        }
        if (changes.term().present() != changes.termUnit().present()
                || changes.term().clears() != changes.termUnit().clears()) {
            errors.put(changes.term().present() ? "termUnit" : "term", "Срок и единица срока передаются вместе");
        }
        if (changes.collateral().clears()) {
            errors.put("collateral", "Поле нельзя очистить");
        } else if (Boolean.TRUE.equals(changes.collateral().value())) {
            errors.put("collateral", "Пока доступны только кредиты без залога");
        }
        if (changes.subscription().clears()) {
            errors.put("subscription", "Поле нельзя очистить");
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Некоторые поля заполнены неверно", errors);
        }
    }

    private static void requireForSubmit(Map<String, String> missing, String field, Object value) {
        if (value == null) {
            missing.put(field, "Обязательно для отправки заявки");
        }
    }
}
