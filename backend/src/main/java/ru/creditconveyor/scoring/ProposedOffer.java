package ru.creditconveyor.scoring;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Условия, которые политика предлагает клиенту.
 *
 * @param subscriptionFee комиссия за подписку единоразово при выдаче; нет — без комиссии
 * @param preliminary     сумма больше N: после согласия клиента условия подтверждает андеррайтер
 */
record ProposedOffer(
        String productCode,
        String productName,
        BigDecimal amount,
        int term,
        TermUnitDto termUnit,
        TermKindDto termKind,
        @Nullable Integer agreementTermMonths,
        BigDecimal rate,
        RateUnitDto rateUnit,
        RepaymentTypeDto repaymentType,
        BigDecimal monthlyPayment,
        boolean subscription,
        @Nullable BigDecimal subscriptionFee,
        boolean preliminary) {
}
