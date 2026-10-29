package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.product.ProductTerms;

/**
 * Условия оффера по каталогу: ставка с подпиской или без, комиссия за подписку, ежемесячный платёж.
 * Месяц = 30 дней: ставка в день × 30 — месячная, срок в днях округляется вверх до месяцев.
 */
final class OfferCalculator {

    private static final int DAYS_IN_MONTH = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private OfferCalculator() {
    }

    static ProposedOffer offer(CreditCase credit, BigDecimal amount, boolean preliminary) {
        return offer(credit.product(), amount, credit.term(), credit.termUnit(), credit.repaymentType(),
                credit.subscription(), null, preliminary);
    }

    /**
     * @param repaymentType не поддерживается продуктом или не задан — первый способ погашения продукта
     * @param rate          ставка андеррайтера; {@code null} — по каталогу
     */
    static ProposedOffer offer(ProductTerms product, BigDecimal amount, int term, TermUnitDto termUnit,
                               @Nullable RepaymentTypeDto repaymentType, boolean withSubscription,
                               @Nullable BigDecimal rate, boolean preliminary) {
        boolean subscription = withSubscription && product.rateWithSubscription() != null;
        BigDecimal offerRate = rate != null ? rate : catalogRate(product, subscription);
        RepaymentTypeDto repayment = repaymentType != null && product.repaymentTypes().contains(repaymentType)
                ? repaymentType : product.repaymentTypes().getFirst();
        BigDecimal monthlyRate = monthlyRate(offerRate, product.rateUnit());
        BigDecimal payment = repayment == RepaymentTypeDto.ANNUITY
                ? annuity(amount, monthlyRate, months(term, termUnit))
                : amount.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal fee = subscription ? subscriptionFee(amount, product.subscriptionFeePercent()) : null;
        return new ProposedOffer(product.code(), product.name(), amount, term, termUnit, product.termKind(),
                product.agreementTermMonths(), offerRate, product.rateUnit(), repayment, payment, subscription, fee,
                preliminary);
    }

    static BigDecimal catalogRate(ProductTerms product, boolean withSubscription) {
        return withSubscription && product.rateWithSubscription() != null
                ? product.rateWithSubscription()
                : product.baseRate();
    }

    /** P × r / (1 − (1 + r)^−n) */
    static BigDecimal annuity(BigDecimal amount, BigDecimal monthlyRate, int months) {
        if (monthlyRate.signum() == 0) {
            return amount.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
        BigDecimal growth = BigDecimal.ONE.add(monthlyRate).pow(months, PRECISION);
        BigDecimal factor = monthlyRate.multiply(growth, PRECISION)
                .divide(growth.subtract(BigDecimal.ONE), PRECISION);
        return amount.multiply(factor, PRECISION).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal monthlyRate(BigDecimal rate, RateUnitDto unit) {
        BigDecimal fraction = rate.divide(HUNDRED, PRECISION);
        return unit == RateUnitDto.PERCENT_PER_DAY
                ? fraction.multiply(BigDecimal.valueOf(DAYS_IN_MONTH))
                : fraction.divide(BigDecimal.valueOf(12), PRECISION);
    }

    static int months(int term, TermUnitDto unit) {
        return unit == TermUnitDto.MONTHS ? term : Math.max(1, Math.ceilDiv(term, DAYS_IN_MONTH));
    }

    private static @Nullable BigDecimal subscriptionFee(BigDecimal amount, @Nullable BigDecimal feePercent) {
        return feePercent == null || feePercent.signum() == 0
                ? null
                : amount.multiply(feePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
