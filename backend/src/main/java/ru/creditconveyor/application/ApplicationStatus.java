package ru.creditconveyor.application;

/**
 * Менять статус заявки может только этот модуль, по таблице {@link StatusTransitions}.
 */
public enum ApplicationStatus {
    DRAFT,
    SUBMITTED,
    ENRICHMENT,
    SCORING,
    NEED_INFO,
    MANUAL_REVIEW,
    APPROVED,
    COUNTER_OFFER,
    PENDING_CONFIRMATION,
    OFFER_ACCEPTED,
    SIGNED,
    DISBURSED,
    REJECTED,
    OFFER_DECLINED,
    OFFER_EXPIRED,
    CANCELLED;

    public boolean isFinal() {
        return this == DISBURSED || this == REJECTED || this == OFFER_DECLINED || this == OFFER_EXPIRED
                || this == CANCELLED;
    }
}
