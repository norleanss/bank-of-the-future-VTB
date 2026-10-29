package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.TermUnitDto;

/**
 * Проверка заявки по условиям продукта — те же правила, что в подборе ({@link ProductRules}): сумма, срок с учётом
 * подписки и возраст бизнеса.
 */
@Service
@Transactional(readOnly = true)
public class ProductConditions {

    public record Violation(String field, String message) {
    }

    private final CreditProductRepository products;

    ProductConditions(CreditProductRepository products) {
        this.products = products;
    }

    public List<Violation> check(String productCode, BigDecimal amount, int term, TermUnitDto termUnit,
                                 boolean subscription, int businessAgeMonths) {
        CreditProduct product = products.findByCodeAndActiveTrue(productCode).orElse(null);
        if (product == null) {
            return List.of(new Violation("productCode", "Продукт недоступен"));
        }
        List<Violation> violations = new ArrayList<>();
        if (ProductRules.businessAgeReason(product, businessAgeMonths) != null) {
            violations.add(new Violation("productCode", "Продукт «%s» доступен бизнесу от %s, компании — %s".formatted(
                    product.getName(),
                    ProductRules.termUpTo(product.getMinBusinessAgeMonths(), TermUnitDto.MONTHS),
                    ProductRules.term(businessAgeMonths, TermUnitDto.MONTHS))));
        }
        String amountReason = ProductRules.amountReason(product, amount);
        if (amountReason != null) {
            violations.add(new Violation("requestedAmount", amountReason));
        }
        switch (ProductRules.termFit(product, term, termUnit)) {
            case TOO_SHORT -> violations.add(new Violation("term", ProductRules.minTermReason(product)));
            case TOO_LONG -> violations.add(new Violation("term", ProductRules.maxTermReason(product)));
            case FITS_WITH_SUBSCRIPTION -> {
                if (!subscription) {
                    violations.add(new Violation("term", "Такой срок — только с подпиской. "
                            + ProductRules.subscriptionReason(product)));
                }
            }
            case FITS -> {
            }
        }
        return violations;
    }
}
