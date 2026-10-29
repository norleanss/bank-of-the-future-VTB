package ru.creditconveyor.application;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Условия оффера от кредитной политики или андеррайтера.
 *
 * @param preliminary после согласия клиента условия подтверждает андеррайтер
 */
public record OfferTerms(
        ProductCodeDto productCode,
        String productName,
        BigDecimal amount,
        int term,
        TermUnitDto termUnit,
        TermKindDto termKind,
        @Nullable Integer agreementTermMonths,
        BigDecimal rate,
        RateUnitDto rateUnit,
        @Nullable BigDecimal monthlyPayment,
        boolean subscription,
        @Nullable BigDecimal subscriptionFee,
        boolean preliminary) {
}
