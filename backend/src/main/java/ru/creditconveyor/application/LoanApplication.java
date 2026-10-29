package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Статус меняется только методами этого класса по {@link StatusTransitions}, каждый переход — в историю;
 * {@code @Version}: из одновременных изменений второе получает 409. После согласия клиента оффер не истекает.
 * Каждый переход публикуется событием {@link ApplicationStatusChanged} при {@code save} в репозитории — после
 * изменения заявку нужно сохранить, иначе транзакция не зафиксируется.
 */
@Entity
@Table(schema = "application", name = "applications")
class LoanApplication {

    static final Set<ApplicationStatus> OFFERED = EnumSet.of(ApplicationStatus.APPROVED, ApplicationStatus.COUNTER_OFFER);

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "applicant_id", nullable = false, updatable = false)
    private UUID applicantId;

    @Column(name = "product_code")
    private String productCode;

    private String purpose;

    @Column(name = "requested_amount")
    private BigDecimal requestedAmount;

    private Integer term;

    @Column(name = "term_unit")
    private String termUnit;

    @Column(name = "funding_mode")
    private String fundingMode;

    @Column(name = "repayment_type")
    private String repaymentType;

    @Column(name = "annual_revenue")
    private BigDecimal annualRevenue;

    @Column(name = "annual_expenses")
    private BigDecimal annualExpenses;

    @Column(name = "bank_monthly_turnover")
    private BigDecimal bankMonthlyTurnover;

    @Column(name = "monthly_debt_payment")
    private BigDecimal monthlyDebtPayment;

    @Column(nullable = false)
    private boolean collateral;

    @Column(nullable = false)
    private boolean subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApplicationStatus status;

    @Version
    private Long version;

    /** Номер круга автоматической обработки: растёт при отправке и при ответе клиента на запрос банка. */
    @Column(name = "processing_round", nullable = false)
    private int processingRound;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** С какого момента заявка в текущем статусе: порядок очередей бэк-офиса. */
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<StatusHistoryEntry> history = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    private List<LoanOffer> offers = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    @OrderBy("createdAt")
    private List<InfoRequest> infoRequests = new ArrayList<>();

    /** Причины последнего решения для клиента; при новой проверке очищаются. */
    @ElementCollection
    @CollectionTable(schema = "application", name = "application_reasons",
            joinColumns = @JoinColumn(name = "application_id"))
    @OrderColumn(name = "position")
    private List<StoredReason> reasons = new ArrayList<>();

    /** Переходы, ещё не опубликованные событием. */
    @Transient
    private final List<StatusChange> statusChanges = new ArrayList<>();

    private record StatusChange(ApplicationStatus from, ApplicationStatus to, ActorType actorType, Instant at) {
    }

    protected LoanApplication() {
    }

    static LoanApplication draft(UUID id, String number, UUID companyId, Actor applicant, Instant now) {
        LoanApplication application = new LoanApplication();
        application.id = id;
        application.number = number;
        application.companyId = companyId;
        application.applicantId = applicant.id();
        application.status = ApplicationStatus.DRAFT;
        application.createdAt = now;
        application.updatedAt = now;
        application.statusChangedAt = now;
        application.history.add(new StatusHistoryEntry(null, ApplicationStatus.DRAFT, applicant, null, now));
        return application;
    }

    /** В {@code APPROVED} и {@code COUNTER_OFFER} — только через {@link #makeOffer}, с условиями. */
    void changeStatus(ApplicationStatus to, Actor actor, String comment, Instant now) {
        requireAllowed(to, actor);
        if (OFFERED.contains(to)) {
            throw new IllegalStateException("Одобрение и контрпредложение — только с условиями: makeOffer");
        }
        move(to, actor, comment, now);
    }

    void makeOffer(ApplicationStatus to, OfferTerms terms, Actor actor, String comment, Instant now, Duration validity) {
        if (!OFFERED.contains(to)) {
            throw new IllegalArgumentException("Предложение возможно только в статусах APPROVED и COUNTER_OFFER");
        }
        requireAllowed(to, actor);
        currentOffer().ifPresent(LoanOffer::supersede);
        offers.add(new LoanOffer(terms, now, now.plus(validity)));
        move(to, actor, comment, now);
    }

    void replaceReasons(List<DecisionReason> decisionReasons) {
        reasons.clear();
        decisionReasons.forEach(reason -> reasons.add(new StoredReason(reason)));
    }

    /** {@code NEED_INFO} — только с запросом: без него клиенту не на что ответить. */
    void requestInfo(String message, List<String> fields, List<String> documents, Actor actor, String comment,
                     Instant now) {
        requireAllowed(ApplicationStatus.NEED_INFO, actor);
        infoRequests.add(new InfoRequest(message, fields, documents, actor.type(), now));
        move(ApplicationStatus.NEED_INFO, actor, comment, now);
    }

    Optional<InfoRequest> openInfoRequest() {
        return infoRequests.stream().filter(InfoRequest::isOpen).findFirst();
    }

    /**
     * Ответ закрывает открытый запрос. Запрос системы — заявка снова проходит автоматическую проверку;
     * запрос андеррайтера или {@code toUnderwriter} — к андеррайтеру, без автоматического решения.
     */
    void answerInfoRequest(String response, Actor client, Instant now, boolean toUnderwriter) {
        InfoRequest request = openInfoRequest()
                .filter(open -> status == ApplicationStatus.NEED_INFO)
                .orElseThrow(() -> InvalidStatusTransitionException.action("ответить на запрос банка", status));
        ApplicationStatus next = toUnderwriter || ActorType.EMPLOYEE.name().equals(request.getCreatedBy())
                ? ApplicationStatus.MANUAL_REVIEW
                : ApplicationStatus.ENRICHMENT;
        requireAllowed(next, client);
        request.answer(response, now);
        move(next, client, response, now);
    }

    /** Предварительное одобрение уходит в {@code PENDING_CONFIRMATION}, обычное — в {@code OFFER_ACCEPTED}. */
    void acceptOffer(Actor client, Instant now, ZoneId zone) {
        LoanOffer offer = requireLiveOffer("принять предложение", now, zone);
        move(offer.isPreliminary() ? ApplicationStatus.PENDING_CONFIRMATION : ApplicationStatus.OFFER_ACCEPTED,
                client, null, now);
    }

    void declineOffer(Actor client, String comment, Instant now, ZoneId zone) {
        requireLiveOffer("отказаться от предложения", now, zone);
        move(ApplicationStatus.OFFER_DECLINED, client, comment, now);
    }

    /** @return истекло ли предложение сейчас; переход — от имени системы */
    boolean expireOfferIfDue(Instant now) {
        if (!OFFERED.contains(status) || currentOffer().filter(offer -> offer.isDue(now)).isEmpty()) {
            return false;
        }
        move(ApplicationStatus.OFFER_EXPIRED, Actor.system(), "Срок предложения истёк", now);
        return true;
    }

    Optional<LoanOffer> currentOffer() {
        return offers.stream().filter(offer -> offer.getStatus() != OfferStatus.SUPERSEDED).reduce((first, second) -> second);
    }

    private LoanOffer requireLiveOffer(String action, Instant now, ZoneId zone) {
        Optional<LoanOffer> offer = currentOffer();
        boolean expired = status == ApplicationStatus.OFFER_EXPIRED
                || (OFFERED.contains(status) && offer.filter(o -> o.isDue(now)).isPresent());
        if (expired && offer.isPresent()) {
            throw new OfferExpiredException(offer.get().getExpiresAt(), zone);
        }
        if (!OFFERED.contains(status) || offer.isEmpty()) {
            throw InvalidStatusTransitionException.action(action, status);
        }
        return offer.get();
    }

    private void requireAllowed(ApplicationStatus to, Actor actor) {
        if (!StatusTransitions.isAllowed(status, to, actor.type())) {
            throw InvalidStatusTransitionException.transition(status, to);
        }
    }

    private void move(ApplicationStatus to, Actor actor, String comment, Instant now) {
        requireAllowed(to, actor);
        history.add(new StatusHistoryEntry(status, to, actor, comment, now));
        statusChanges.add(new StatusChange(status, to, actor.type(), now));
        // Банк отказал после согласия клиента — принятое предложение больше не действует
        if (status == ApplicationStatus.PENDING_CONFIRMATION && to == ApplicationStatus.REJECTED) {
            currentOffer().ifPresent(LoanOffer::decline);
        }
        currentOffer().filter(offer -> offer.getStatus() == OfferStatus.ACTIVE).ifPresent(offer -> {
            switch (to) {
                case OFFER_ACCEPTED, PENDING_CONFIRMATION -> offer.accept();
                case OFFER_DECLINED -> offer.decline();
                case OFFER_EXPIRED -> offer.expire();
                default -> {
                }
            }
        });
        if (to == ApplicationStatus.SUBMITTED) {
            submittedAt = now;
        }
        boolean newRound = to == ApplicationStatus.SUBMITTED
                || (to == ApplicationStatus.ENRICHMENT && status == ApplicationStatus.NEED_INFO);
        if (newRound) {
            processingRound++;
        }
        if (to == ApplicationStatus.ENRICHMENT) {
            reasons.clear();
        }
        status = to;
        updatedAt = now;
        statusChangedAt = now;
    }

    /** Данные заявки — на момент сохранения: причины и оффер нового решения уже на месте. */
    @DomainEvents
    List<ApplicationStatusChanged> statusEvents() {
        ApplicationStatusChanged.Offer offer = currentOffer()
                .map(o -> new ApplicationStatusChanged.Offer(o.getProductName(), o.getAmount(), o.getTerm(),
                        TermUnitDto.fromValue(o.getTermUnit()), TermKindDto.fromValue(o.getTermKind()), o.isPreliminary(),
                        o.getExpiresAt()))
                .orElse(null);
        String infoRequestMessage = openInfoRequest().map(InfoRequest::getMessage).orElse(null);
        return statusChanges.stream()
                .map(change -> new ApplicationStatusChanged(UUID.randomUUID(), id, number, companyId, applicantId,
                        change.from(), change.to(), change.actorType(), change.at(), getReasons(), offer,
                        infoRequestMessage))
                .toList();
    }

    @AfterDomainEventPublication
    void clearStatusEvents() {
        statusChanges.clear();
    }

    /** Страховка: переход, о котором никто не узнал, — ошибка в коде, а не тихая потеря уведомления. */
    @PrePersist
    @PreUpdate
    void requirePublishedStatusEvents() {
        if (!statusChanges.isEmpty()) {
            throw new IllegalStateException("Статус заявки " + id + " изменён без публикации события: "
                    + "после изменения вызовите save()");
        }
    }

    /** Заполнено ли поле анкеты по пути из запроса сведений; поля вне анкеты не проверяем. */
    boolean isFilled(String field) {
        Object value = switch (field) {
            case "productCode" -> productCode;
            case "purpose" -> purpose;
            case "requestedAmount" -> requestedAmount;
            case "term", "termUnit" -> term;
            case "fundingMode" -> fundingMode;
            case "repaymentType" -> repaymentType;
            case "financials.annualRevenue" -> annualRevenue;
            case "financials.annualExpenses" -> annualExpenses;
            case "financials.bankMonthlyTurnover" -> bankMonthlyTurnover;
            case "financials.monthlyDebtPayment" -> monthlyDebtPayment;
            default -> Boolean.TRUE;
        };
        return value != null;
    }

    /** Поля, которые изменение действительно меняет (путь как в {@code PATCH}); то же значение — не изменение. */
    List<String> changedFields(DraftChanges changes) {
        List<String> changed = new ArrayList<>();
        addIfChanged(changed, "productCode", changes.productCode(), productCode);
        addIfChanged(changed, "purpose", changes.purpose(), purpose);
        addIfChanged(changed, "requestedAmount", changes.requestedAmount(), requestedAmount);
        addIfChanged(changed, "term", changes.term(), term);
        addIfChanged(changed, "termUnit", changes.termUnit(), termUnit);
        addIfChanged(changed, "fundingMode", changes.fundingMode(), fundingMode);
        addIfChanged(changed, "repaymentType", changes.repaymentType(), repaymentType);
        addIfChanged(changed, "financials.annualRevenue", changes.annualRevenue(), annualRevenue);
        addIfChanged(changed, "financials.annualExpenses", changes.annualExpenses(), annualExpenses);
        addIfChanged(changed, "financials.bankMonthlyTurnover", changes.bankMonthlyTurnover(), bankMonthlyTurnover);
        addIfChanged(changed, "financials.monthlyDebtPayment", changes.monthlyDebtPayment(), monthlyDebtPayment);
        addIfChanged(changed, "collateral", changes.collateral(), collateral);
        addIfChanged(changed, "subscription", changes.subscription(), subscription);
        return changed;
    }

    private static <T> void addIfChanged(List<String> changed, String field, FieldChange<T> change, T current) {
        if (!change.present()) {
            return;
        }
        T value = change.value();
        boolean same = value instanceof BigDecimal amount && current instanceof BigDecimal stored
                ? amount.compareTo(stored) == 0
                : Objects.equals(value, current);
        if (!same) {
            changed.add(field);
        }
    }

    boolean isEditable() {
        return status == ApplicationStatus.DRAFT || status == ApplicationStatus.NEED_INFO;
    }

    void applyChanges(DraftChanges changes, Instant now) {
        if (!isEditable()) {
            throw InvalidStatusTransitionException.action("изменить заявку", status);
        }
        productCode = changes.productCode().applyTo(productCode);
        purpose = changes.purpose().applyTo(purpose);
        requestedAmount = changes.requestedAmount().applyTo(requestedAmount);
        term = changes.term().applyTo(term);
        termUnit = changes.termUnit().applyTo(termUnit);
        fundingMode = changes.fundingMode().applyTo(fundingMode);
        repaymentType = changes.repaymentType().applyTo(repaymentType);
        annualRevenue = changes.annualRevenue().applyTo(annualRevenue);
        annualExpenses = changes.annualExpenses().applyTo(annualExpenses);
        bankMonthlyTurnover = changes.bankMonthlyTurnover().applyTo(bankMonthlyTurnover);
        monthlyDebtPayment = changes.monthlyDebtPayment().applyTo(monthlyDebtPayment);
        collateral = changes.collateral().applyTo(collateral);
        subscription = changes.subscription().applyTo(subscription);
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getNumber() {
        return number;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getApplicantId() {
        return applicantId;
    }

    String getProductCode() {
        return productCode;
    }

    String getPurpose() {
        return purpose;
    }

    BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    Integer getTerm() {
        return term;
    }

    String getTermUnit() {
        return termUnit;
    }

    String getFundingMode() {
        return fundingMode;
    }

    String getRepaymentType() {
        return repaymentType;
    }

    BigDecimal getAnnualRevenue() {
        return annualRevenue;
    }

    BigDecimal getAnnualExpenses() {
        return annualExpenses;
    }

    BigDecimal getBankMonthlyTurnover() {
        return bankMonthlyTurnover;
    }

    BigDecimal getMonthlyDebtPayment() {
        return monthlyDebtPayment;
    }

    boolean isCollateral() {
        return collateral;
    }

    boolean isSubscription() {
        return subscription;
    }

    ApplicationStatus getStatus() {
        return status;
    }

    int getProcessingRound() {
        return processingRound;
    }

    Long getVersion() {
        return version;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getSubmittedAt() {
        return submittedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    List<InfoRequest> getInfoRequests() {
        return Collections.unmodifiableList(infoRequests);
    }

    List<StatusHistoryEntry> getHistory() {
        return Collections.unmodifiableList(history);
    }

    List<DecisionReason> getReasons() {
        return reasons.stream().map(StoredReason::toReason).toList();
    }
}
