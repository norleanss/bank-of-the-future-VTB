package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermKindDto;

/**
 * Условия продукта для расчёта решения и оффера.
 *
 * @param agreementTermMonths  срок договора (лимита); нет у кредита одной суммой
 * @param minBusinessAgeMonths с какого возраста бизнеса продукт доступен
 */
public record ProductTerms(
        String code,
        String name,
        TermKindDto termKind,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Nullable Integer agreementTermMonths,
        RateUnitDto rateUnit,
        BigDecimal baseRate,
        @Nullable BigDecimal rateWithSubscription,
        @Nullable BigDecimal subscriptionFeePercent,
        List<RepaymentTypeDto> repaymentTypes,
        int minBusinessAgeMonths) {

    static ProductTerms of(CreditProduct product) {
        return new ProductTerms(product.getCode(), product.getName(), product.getTermKind(), product.getMinAmount(),
                product.getMaxAmount(),
                product.getAgreementTermMonths(), product.getRateUnit(), product.getBaseRate(),
                product.getRateWithSubscription(), product.getSubscriptionFeePercent(), product.getRepaymentTypes(),
                product.getMinBusinessAgeMonths());
    }
}
