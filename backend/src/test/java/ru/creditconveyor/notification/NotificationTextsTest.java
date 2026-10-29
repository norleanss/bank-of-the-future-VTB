package ru.creditconveyor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.application.ActorType;
import ru.creditconveyor.application.ApplicationStatus;
import ru.creditconveyor.application.ApplicationStatusChanged;

/**
 * У линий, овердрафта и Гамма-Старта — лимит и срок транша, у кредита одной суммой — срок возврата.
 */
class NotificationTextsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Instant EXPIRES = Instant.parse("2026-11-08T09:00:00Z");

    @Test
    void trancheProductsShowLimitAndTrancheTerm() {
        var overdraft = new ApplicationStatusChanged.Offer("Овердрафт для бизнеса", new BigDecimal("800000.00"), 60,
                TermUnitDto.DAYS, TermKindDto.TRANCHE, false, EXPIRES);

        assertThat(body(ApplicationStatus.APPROVED, overdraft))
                .isEqualTo("Одобрен лимит 800 000 ₽, транши до 60 дней. Примите предложение до 08.11.2026.");
        assertThat(body(ApplicationStatus.COUNTER_OFFER, overdraft)).isEqualTo("По заявке CR-2026-000001 доступно: "
                + "лимит 800 000 ₽, транши до 60 дней («Овердрафт для бизнеса»). Ответьте на предложение до 08.11.2026.");
        assertThat(body(ApplicationStatus.DISBURSED, overdraft))
                .isEqualTo("Лимит 800 000 ₽ по заявке CR-2026-000001 открыт — берите транши до 60 дней на счёт компании.");
    }

    @Test
    void lumpSumLoanShowsRepaymentTerm() {
        var loan = new ApplicationStatusChanged.Offer("Кредит одной суммой", new BigDecimal("5000000.00"), 36,
                TermUnitDto.MONTHS, TermKindDto.LOAN, true, EXPIRES);

        assertThat(body(ApplicationStatus.APPROVED, loan)).isEqualTo("Предварительно одобрено 5 000 000 ₽ на 36 месяцев. "
                + "Примите предложение до 08.11.2026 — после этого условия подтвердит специалист банка.");
        assertThat(body(ApplicationStatus.DISBURSED, loan))
                .isEqualTo("5 000 000 ₽ по заявке CR-2026-000001 перечислены на счёт компании.");
    }

    @Test
    void ownClientActionsAndInternalStepsAreSilent() {
        for (ApplicationStatus status : List.of(ApplicationStatus.ENRICHMENT, ApplicationStatus.SCORING,
                ApplicationStatus.PENDING_CONFIRMATION, ApplicationStatus.OFFER_DECLINED, ApplicationStatus.CANCELLED)) {
            assertThat(NotificationTexts.of(event(status, ActorType.SYSTEM, null), ZONE)).as(status.name()).isEmpty();
        }
        assertThat(NotificationTexts.of(event(ApplicationStatus.MANUAL_REVIEW, ActorType.CLIENT, null), ZONE)).isEmpty();
        assertThat(NotificationTexts.of(event(ApplicationStatus.OFFER_ACCEPTED, ActorType.CLIENT, null), ZONE)).isEmpty();
        assertThat(NotificationTexts.of(event(ApplicationStatus.OFFER_ACCEPTED, ActorType.EMPLOYEE, null), ZONE))
                .hasValueSatisfying(text -> assertThat(text.type()).isEqualTo("OFFER_CONFIRMED"));
    }

    private static String body(ApplicationStatus status, ApplicationStatusChanged.Offer offer) {
        return NotificationTexts.of(event(status, ActorType.SYSTEM, offer), ZONE).orElseThrow().body();
    }

    private static ApplicationStatusChanged event(ApplicationStatus status, ActorType actor,
                                                  ApplicationStatusChanged.Offer offer) {
        return new ApplicationStatusChanged(UUID.randomUUID(), UUID.randomUUID(), "CR-2026-000001", UUID.randomUUID(),
                UUID.randomUUID(), null, status, actor, EXPIRES, List.of(), offer, null);
    }
}
