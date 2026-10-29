package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Решение политики.
 *
 * @param missingFields для {@link Outcome#NEED_INFO} — чего не хватает в анкете
 * @param scoring       нет, если решение принято до скоринга
 * @param limit         лимит по финансам; нет, если до расчёта лимита не дошли
 * @param offer         для {@link Outcome#APPROVE} и {@link Outcome#COUNTER_OFFER}
 */
record Decision(
        Outcome outcome,
        List<Reason> reasons,
        List<String> missingFields,
        @Nullable ScoreResult scoring,
        @Nullable BigDecimal limit,
        @Nullable ProposedOffer offer) {

    enum Outcome {
        APPROVE,
        COUNTER_OFFER,
        NEED_INFO,
        MANUAL_REVIEW,
        REJECT
    }

    static Decision of(Outcome outcome, List<Reason> reasons, @Nullable ScoreResult scoring) {
        return new Decision(outcome, List.copyOf(reasons), List.of(), scoring, null, null);
    }
}
