package ru.creditconveyor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.creditconveyor.api.ApplicationActionDto.ACCEPT_OFFER;
import static ru.creditconveyor.api.ApplicationActionDto.CANCEL;
import static ru.creditconveyor.api.ApplicationActionDto.DECLINE_OFFER;
import static ru.creditconveyor.api.ApplicationActionDto.EDIT;
import static ru.creditconveyor.api.ApplicationActionDto.RESPOND_INFO;
import static ru.creditconveyor.api.ApplicationActionDto.SIGN;
import static ru.creditconveyor.api.ApplicationActionDto.SUBMIT;
import static ru.creditconveyor.api.ApplicationActionDto.UPLOAD_DOCUMENT;
import static ru.creditconveyor.application.ApplicationStatus.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import ru.creditconveyor.api.ApplicationActionDto;
import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Машина состояний без базы: таблица переходов, запрет остального, оффер и кнопки клиента.
 */
class StatusTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final Duration VALIDITY = Duration.ofDays(7);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    /** Независимая копия согласованной таблицы переходов — чтобы тест ловил расхождения с кодом. */
    static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(DRAFT, SUBMITTED, ActorType.CLIENT),
                Arguments.of(SUBMITTED, ENRICHMENT, ActorType.SYSTEM),
                Arguments.of(ENRICHMENT, SCORING, ActorType.SYSTEM),
                Arguments.of(ENRICHMENT, MANUAL_REVIEW, ActorType.SYSTEM),
                Arguments.of(ENRICHMENT, REJECTED, ActorType.SYSTEM),
                Arguments.of(SCORING, REJECTED, ActorType.SYSTEM),
                Arguments.of(SCORING, NEED_INFO, ActorType.SYSTEM),
                Arguments.of(SCORING, MANUAL_REVIEW, ActorType.SYSTEM),
                Arguments.of(SCORING, COUNTER_OFFER, ActorType.SYSTEM),
                Arguments.of(SCORING, APPROVED, ActorType.SYSTEM),
                Arguments.of(NEED_INFO, ENRICHMENT, ActorType.CLIENT),
                Arguments.of(NEED_INFO, MANUAL_REVIEW, ActorType.CLIENT),
                Arguments.of(MANUAL_REVIEW, APPROVED, ActorType.EMPLOYEE),
                Arguments.of(MANUAL_REVIEW, COUNTER_OFFER, ActorType.EMPLOYEE),
                Arguments.of(MANUAL_REVIEW, NEED_INFO, ActorType.EMPLOYEE),
                Arguments.of(MANUAL_REVIEW, REJECTED, ActorType.EMPLOYEE),
                Arguments.of(APPROVED, OFFER_ACCEPTED, ActorType.CLIENT),
                Arguments.of(COUNTER_OFFER, OFFER_ACCEPTED, ActorType.CLIENT),
                Arguments.of(APPROVED, PENDING_CONFIRMATION, ActorType.CLIENT),
                Arguments.of(COUNTER_OFFER, PENDING_CONFIRMATION, ActorType.CLIENT),
                Arguments.of(APPROVED, OFFER_DECLINED, ActorType.CLIENT),
                Arguments.of(COUNTER_OFFER, OFFER_DECLINED, ActorType.CLIENT),
                Arguments.of(APPROVED, OFFER_EXPIRED, ActorType.SYSTEM),
                Arguments.of(COUNTER_OFFER, OFFER_EXPIRED, ActorType.SYSTEM),
                Arguments.of(PENDING_CONFIRMATION, OFFER_ACCEPTED, ActorType.EMPLOYEE),
                Arguments.of(PENDING_CONFIRMATION, COUNTER_OFFER, ActorType.EMPLOYEE),
                Arguments.of(PENDING_CONFIRMATION, REJECTED, ActorType.EMPLOYEE),
                Arguments.of(OFFER_ACCEPTED, SIGNED, ActorType.CLIENT),
                Arguments.of(SIGNED, DISBURSED, ActorType.SYSTEM),
                Arguments.of(DRAFT, CANCELLED, ActorType.CLIENT),
                Arguments.of(NEED_INFO, CANCELLED, ActorType.CLIENT),
                Arguments.of(MANUAL_REVIEW, CANCELLED, ActorType.CLIENT));
    }

    /** Одобрение и контрпредложение — с условиями, истечение — по сроку оффера. */
    @ParameterizedTest(name = "{0} → {1} ({2})")
    @MethodSource("allowedTransitions")
    void allowsTransitionFromTable(ApplicationStatus from, ApplicationStatus to, ActorType actorType) {
        assertThat(StatusTransitions.isAllowed(from, to, actorType)).isTrue();

        LoanApplication application = applicationIn(from);
        Actor actor = new Actor(actorType, actorType == ActorType.SYSTEM ? null : UUID.randomUUID());
        String expectedComment = "комментарий";
        Instant expectedTime = LATER;
        if (LoanApplication.OFFERED.contains(to)) {
            application.makeOffer(to, terms(false), actor, "комментарий", LATER, VALIDITY);
        } else if (to == OFFER_EXPIRED) {
            expectedTime = NOW.plus(VALIDITY);
            expectedComment = "Срок предложения истёк";
            assertThat(application.expireOfferIfDue(expectedTime)).isTrue();
        } else {
            application.changeStatus(to, actor, "комментарий", LATER);
        }

        assertThat(application.getStatus()).isEqualTo(to);
        StatusHistoryEntry last = application.getHistory().getLast();
        assertThat(last.getFromStatus()).isEqualTo(from);
        assertThat(last.getToStatus()).isEqualTo(to);
        assertThat(last.getActorType()).isEqualTo(actorType);
        assertThat(last.getComment()).isEqualTo(expectedComment);
        assertThat(last.getCreatedAt()).isEqualTo(expectedTime);
    }

    @Test
    void tableContainsNothingElse() {
        assertThat(StatusTransitions.ALLOWED).hasSize((int) allowedTransitions().count());
    }

    @Test
    void rejectsEveryTransitionMissingFromTable() {
        Set<StatusTransitions.Transition> allowed = StatusTransitions.ALLOWED;
        List<StatusTransitions.Transition> checked = new ArrayList<>();
        for (ApplicationStatus from : ApplicationStatus.values()) {
            for (ApplicationStatus to : ApplicationStatus.values()) {
                for (ActorType actor : ActorType.values()) {
                    var transition = new StatusTransitions.Transition(from, to, actor);
                    if (allowed.contains(transition)) {
                        continue;
                    }
                    LoanApplication application = applicationIn(from);
                    int historySize = application.getHistory().size();
                    assertThatThrownBy(() -> application.changeStatus(to, new Actor(actor, null), null, NOW))
                            .as("%s → %s (%s)", from, to, actor)
                            .isInstanceOf(InvalidStatusTransitionException.class)
                            .satisfies(ex -> assertThat(((InvalidStatusTransitionException) ex).getStatusCode())
                                    .isEqualTo(HttpStatus.CONFLICT));
                    assertThat(application.getStatus()).isEqualTo(from);
                    assertThat(application.getHistory()).hasSize(historySize);
                    checked.add(transition);
                }
            }
        }
        int all = ApplicationStatus.values().length * ApplicationStatus.values().length * ActorType.values().length;
        assertThat(checked).hasSize(all - allowed.size());
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"DISBURSED", "REJECTED", "OFFER_DECLINED", "OFFER_EXPIRED", "CANCELLED"})
    void finalStatusesHaveNoWayOut(ApplicationStatus status) {
        assertThat(status.isFinal()).isTrue();
        assertThat(StatusTransitions.ALLOWED).noneMatch(t -> t.from() == status);
    }

    @Test
    void approvalRequiresOfferTerms() {
        LoanApplication application = applicationIn(SCORING);

        assertThatThrownBy(() -> application.changeStatus(APPROVED, Actor.system(), null, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(application.getStatus()).isEqualTo(SCORING);
    }

    @Test
    void submitRecordsSubmissionTime() {
        LoanApplication application = applicationIn(DRAFT);
        application.changeStatus(SUBMITTED, Actor.client(UUID.randomUUID()), null, NOW.plusSeconds(5));

        assertThat(application.getSubmittedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(application.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void editingIsAllowedOnlyInDraftAndNeedInfo() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            LoanApplication application = applicationIn(status);
            if (status == DRAFT || status == NEED_INFO) {
                application.applyChanges(subscriptionOn(), NOW);
                assertThat(application.isSubscription()).isTrue();
            } else {
                assertThatThrownBy(() -> application.applyChanges(subscriptionOn(), NOW))
                        .as(status.name())
                        .isInstanceOf(InvalidStatusTransitionException.class);
            }
        }
    }

    // --- Предложение: принятие, отказ, истечение ---

    @Test
    void acceptingRegularOfferMakesItReadyToSign() {
        LoanApplication application = applicationIn(APPROVED);

        application.acceptOffer(Actor.client(UUID.randomUUID()), LATER, ZONE);

        assertThat(application.getStatus()).isEqualTo(OFFER_ACCEPTED);
        assertThat(application.currentOffer()).get().extracting(LoanOffer::getStatus).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void acceptedPreliminaryOfferWaitsForConfirmationAndNeverExpires() {
        LoanApplication application = applicationIn(SCORING);
        application.makeOffer(APPROVED, terms(true), Actor.system(), null, NOW, VALIDITY);

        application.acceptOffer(Actor.client(UUID.randomUUID()), LATER, ZONE);

        assertThat(application.getStatus()).isEqualTo(PENDING_CONFIRMATION);
        assertThat(application.expireOfferIfDue(NOW.plus(Duration.ofDays(365)))).isFalse();
        assertThat(application.getStatus()).isEqualTo(PENDING_CONFIRMATION);
    }

    @Test
    void offerIsLiveUntilDeadlineInclusive() {
        LoanApplication application = applicationIn(APPROVED);

        assertThat(application.expireOfferIfDue(NOW.plus(VALIDITY).minusSeconds(1))).isFalse();
        assertThat(application.expireOfferIfDue(NOW.plus(VALIDITY))).isTrue();
        assertThat(application.getStatus()).isEqualTo(OFFER_EXPIRED);
        assertThat(application.currentOffer()).get().extracting(LoanOffer::getStatus).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void cannotAnswerOfferAfterDeadline() {
        LoanApplication application = applicationIn(APPROVED);
        Instant afterDeadline = NOW.plus(VALIDITY).plusSeconds(1);

        assertThatThrownBy(() -> application.acceptOffer(Actor.client(UUID.randomUUID()), afterDeadline, ZONE))
                .isInstanceOf(OfferExpiredException.class);
        assertThatThrownBy(() -> application.declineOffer(Actor.client(UUID.randomUUID()), null, afterDeadline, ZONE))
                .isInstanceOf(OfferExpiredException.class);
        assertThat(application.getStatus()).isEqualTo(APPROVED);

        application.expireOfferIfDue(afterDeadline);
        assertThatThrownBy(() -> application.acceptOffer(Actor.client(UUID.randomUUID()), afterDeadline, ZONE))
                .isInstanceOf(OfferExpiredException.class)
                .satisfies(ex -> {
                    var problem = ((OfferExpiredException) ex).getBody();
                    assertThat(problem.getType().toString()).isEqualTo("https://credit.example/problems/offer-expired");
                    assertThat(problem.getDetail())
                            .isEqualTo("Предложение действовало до 08.11.2026. Вы можете подать новую заявку");
                    assertThat(problem.getProperties()).containsEntry("expiresAt", "2026-11-08T12:00+03:00");
                });
    }

    @Test
    void counterOfferReplacesPreviousOffer() {
        LoanApplication application = applicationIn(SCORING);
        application.makeOffer(APPROVED, terms(true), Actor.system(), null, NOW, VALIDITY);
        application.acceptOffer(Actor.client(UUID.randomUUID()), NOW, ZONE);
        LoanOffer first = application.currentOffer().orElseThrow();

        application.makeOffer(COUNTER_OFFER, terms(false), Actor.employee(UUID.randomUUID()), "Снижаем сумму", LATER, VALIDITY);

        assertThat(first.getStatus()).isEqualTo(OfferStatus.SUPERSEDED);
        assertThat(application.currentOffer()).get().isNotSameAs(first)
                .extracting(LoanOffer::getStatus).isEqualTo(OfferStatus.ACTIVE);
        assertThat(application.currentOffer().orElseThrow().getExpiresAt()).isEqualTo(LATER.plus(VALIDITY));
    }

    @Test
    void decliningOfferIsFinal() {
        LoanApplication application = applicationIn(COUNTER_OFFER);

        application.declineOffer(Actor.client(UUID.randomUUID()), "Дорого", LATER, ZONE);

        assertThat(application.getStatus()).isEqualTo(OFFER_DECLINED);
        assertThat(application.getHistory().getLast().getComment()).isEqualTo("Дорого");
        assertThat(application.currentOffer()).get().extracting(LoanOffer::getStatus).isEqualTo(OfferStatus.DECLINED);
    }

    @Test
    void availableActionsMatchFrontendMemo() {
        Map<ApplicationStatus, List<ApplicationActionDto>> expected = Map.ofEntries(
                Map.entry(DRAFT, List.of(EDIT, SUBMIT, CANCEL)),
                Map.entry(SUBMITTED, List.of()),
                Map.entry(ENRICHMENT, List.of()),
                Map.entry(SCORING, List.of()),
                Map.entry(NEED_INFO, List.of(RESPOND_INFO, UPLOAD_DOCUMENT, CANCEL)),
                Map.entry(MANUAL_REVIEW, List.of(CANCEL)),
                Map.entry(APPROVED, List.of(ACCEPT_OFFER, DECLINE_OFFER)),
                Map.entry(COUNTER_OFFER, List.of(ACCEPT_OFFER, DECLINE_OFFER)),
                Map.entry(PENDING_CONFIRMATION, List.of()),
                Map.entry(OFFER_ACCEPTED, List.of(SIGN)),
                Map.entry(SIGNED, List.of()),
                Map.entry(DISBURSED, List.of()),
                Map.entry(REJECTED, List.of()),
                Map.entry(OFFER_DECLINED, List.of()),
                Map.entry(OFFER_EXPIRED, List.of()),
                Map.entry(CANCELLED, List.of()));
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(ClientView.availableActions(status)).as(status.name()).isEqualTo(expected.get(status));
            assertThat(ClientView.nextStepHint(status, null, null, ZONE)).as(status.name()).isNotBlank();
        }
        assertThat(ClientView.nextStepHint(OFFER_EXPIRED, null, null, ZONE))
                .isEqualTo("Срок действия предложения истёк. Вы можете подать новую заявку");
    }

    /** Условия словами: у линий и овердрафта — лимит и срок транша, у кредита одной суммой — срок возврата. */
    @Test
    void hintsDescribeOfferTerms() {
        LoanOffer overdraft = new LoanOffer(terms(false), NOW, NOW.plus(VALIDITY));
        LoanOffer preliminaryLoan = new LoanOffer(new OfferTerms(ProductCodeDto.LUMP_SUM_LOAN, "Кредит одной суммой",
                new BigDecimal("5000000.00"), 36, TermUnitDto.MONTHS, TermKindDto.LOAN, null, new BigDecimal("23.50"),
                RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("194854.03"), false, null, true), NOW, NOW.plus(VALIDITY));

        assertThat(ClientView.nextStepHint(APPROVED, overdraft, null, ZONE))
                .isEqualTo("Одобрен лимит 800 000 ₽, транши до 60 дней. Примите предложение до 08.11.2026");
        assertThat(ClientView.nextStepHint(APPROVED, preliminaryLoan, null, ZONE))
                .isEqualTo("Предварительно одобрено 5 000 000 ₽ на 36 месяцев. Примите предложение до 08.11.2026 — "
                        + "после этого условия подтвердит специалист банка");
        assertThat(ClientView.nextStepHint(COUNTER_OFFER, overdraft, null, ZONE))
                .isEqualTo("Банк предлагает другие условия: лимит 800 000 ₽, транши до 60 дней. "
                        + "Примите их до 08.11.2026 или откажитесь");
        assertThat(ClientView.nextStepHint(COUNTER_OFFER, preliminaryLoan, null, ZONE))
                .endsWith("5 000 000 ₽ на 36 месяцев. Примите их до 08.11.2026 или откажитесь. "
                        + "После вашего согласия условия подтвердит специалист банка");
        assertThat(ClientView.nextStepHint(DISBURSED, overdraft, null, ZONE))
                .isEqualTo("Лимит открыт — берите транши на счёт компании по мере необходимости");
        assertThat(ClientView.nextStepHint(DISBURSED, preliminaryLoan, null, ZONE))
                .isEqualTo("Деньги перечислены на счёт компании");
        assertThat(OfferWording.termUpTo(1, TermUnitDto.MONTHS)).isEqualTo("1 месяца");
        assertThat(OfferWording.termUpTo(21, TermUnitDto.DAYS)).isEqualTo("21 дня");
        assertThat(OfferWording.termUpTo(11, TermUnitDto.DAYS)).isEqualTo("11 дней");
    }

    @Test
    void needInfoHintSaysWhatToDo() {
        assertThat(ClientView.nextStepHint(NEED_INFO, null, new InfoRequest("?", List.of("financials.annualRevenue"),
                List.of("FINANCIAL_STATEMENT"), ActorType.EMPLOYEE, NOW), ZONE))
                .isEqualTo("Банку нужны дополнительные сведения — дополните анкету, приложите документы и ответьте на запрос");
        assertThat(ClientView.nextStepHint(NEED_INFO, null, new InfoRequest("?", List.of(), List.of(),
                ActorType.EMPLOYEE, NOW), ZONE))
                .isEqualTo("Банку нужны дополнительные сведения — ответьте на запрос");
    }

    /** Таблица переходов и {@code availableActions} не расходятся: кнопка — только в разрешённый переход и наоборот. */
    @Test
    void actionsAndClientTransitionsAgree() {
        Map<ApplicationActionDto, Set<ApplicationStatus>> targets = Map.of(
                SUBMIT, EnumSet.of(SUBMITTED),
                CANCEL, EnumSet.of(CANCELLED),
                // Ответ на запрос системы — снова проверка, на запрос андеррайтера — к нему
                RESPOND_INFO, EnumSet.of(ENRICHMENT, MANUAL_REVIEW),
                ACCEPT_OFFER, EnumSet.of(OFFER_ACCEPTED, PENDING_CONFIRMATION),
                DECLINE_OFFER, EnumSet.of(OFFER_DECLINED),
                SIGN, EnumSet.of(SIGNED));
        for (ApplicationStatus status : ApplicationStatus.values()) {
            List<ApplicationActionDto> actions = ClientView.availableActions(status);
            for (ApplicationActionDto action : actions) {
                for (ApplicationStatus target : targets.getOrDefault(action, Set.of())) {
                    assertThat(StatusTransitions.isAllowed(status, target, ActorType.CLIENT))
                            .as("%s в статусе %s → %s", action, status, target).isTrue();
                }
            }
            StatusTransitions.ALLOWED.stream()
                    .filter(t -> t.from() == status && t.actor() == ActorType.CLIENT)
                    .forEach(t -> assertThat(actions)
                            .as("переход клиента %s → %s без кнопки", t.from(), t.to())
                            .anyMatch(action -> targets.getOrDefault(action, Set.of()).contains(t.to())));
        }
    }

    static OfferTerms terms(boolean preliminary) {
        return new OfferTerms(ProductCodeDto.OVERDRAFT, "Овердрафт для бизнеса", new BigDecimal("800000.00"),
                60, TermUnitDto.DAYS, TermKindDto.TRANCHE, 12, new BigDecimal("13.00"), RateUnitDto.PERCENT_PER_YEAR,
                new BigDecimal("8666.67"), true, new BigDecimal("8000.00"), preliminary);
    }

    private static DraftChanges subscriptionOn() {
        return new DraftChanges(FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.absent(), FieldChange.absent(), FieldChange.absent(), FieldChange.absent(),
                FieldChange.set(true));
    }

    /** Статус ставится через поле в обход машины состояний; в {@code APPROVED}/{@code COUNTER_OFFER} — с оффером. */
    private static LoanApplication applicationIn(ApplicationStatus status) {
        LoanApplication application = LoanApplication.draft(UUID.randomUUID(), "CR-2026-000001",
                UUID.randomUUID(), Actor.client(UUID.randomUUID()), NOW);
        if (LoanApplication.OFFERED.contains(status)) {
            ReflectionTestUtils.setField(application, "status", SCORING);
            application.makeOffer(status, terms(false), Actor.system(), null, NOW, VALIDITY);
        } else {
            ReflectionTestUtils.setField(application, "status", status);
        }
        return application;
    }
}
