package ru.creditconveyor.product;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Предварительный платёж для подбора — по тем же формулам, что в предложении. Реализует модуль скоринга (там
 * расчёт оффера): он зависит от каталога, а не наоборот.
 */
public interface PaymentEstimator {

    /**
     * @param subscription на условиях с подпиской
     * @return платёж и комиссию по основному способу погашения продукта
     */
    Estimate estimate(ProductTerms product, BigDecimal amount, int term, TermUnitDto termUnit, boolean subscription);

    /** @param subscriptionFee комиссия за подписку единоразово; нет — без комиссии */
    record Estimate(BigDecimal monthlyPayment, @Nullable BigDecimal subscriptionFee, RepaymentTypeDto repaymentType) {
    }
}
