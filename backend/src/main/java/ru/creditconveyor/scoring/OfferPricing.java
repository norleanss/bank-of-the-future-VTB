package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.application.OfferTerms;
import ru.creditconveyor.product.PaymentEstimator;
import ru.creditconveyor.product.ProductCatalog;
import ru.creditconveyor.product.ProductTerms;

/**
 * Условия оффера андеррайтера и предварительный платёж подбора — по тем же правилам, что у политики: ставка
 * и комиссия из каталога, платёж. Оффер сотрудника не предварительный: условия уже проверил человек.
 */
@Service
public class OfferPricing implements PaymentEstimator {

    private final ProductCatalog catalog;

    OfferPricing(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    /** Ставка каталога с подпиской или без; продукт недоступен — пусто. */
    public Optional<BigDecimal> catalogRate(String productCode, boolean subscription) {
        return catalog.terms(productCode).map(product -> OfferCalculator.catalogRate(product, subscription));
    }

    /** Для подбора: способ погашения — основной у продукта, как в предложении без выбора клиента. */
    @Override
    public Estimate estimate(ProductTerms product, BigDecimal amount, int term, TermUnitDto termUnit,
                             boolean subscription) {
        ProposedOffer offer = OfferCalculator.offer(product, amount, term, termUnit, null, subscription, null, false);
        return new Estimate(offer.monthlyPayment(), offer.subscriptionFee(), offer.repaymentType());
    }

    /** @param rate {@code null} — ставка каталога */
    public Optional<OfferTerms> price(String productCode, BigDecimal amount, int term, TermUnitDto termUnit,
                                      @Nullable RepaymentTypeDto repaymentType, boolean subscription,
                                      @Nullable BigDecimal rate) {
        return catalog.terms(productCode).map(product -> CreditDecisions.terms(OfferCalculator.offer(
                product, amount, term, termUnit, repaymentType, subscription, rate, false)));
    }
}
