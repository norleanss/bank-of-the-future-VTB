package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.PurposeDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Подбор продукта: подписку клиент не передаёт, проверяются оба варианта. Цель кредита не исключает продукт,
 * а задаёт порядок рекомендации; бизнесу младше {@value #YOUNG_BUSINESS_MONTHS} мес. первым идёт Гамма-Старт.
 */
@Component
class ProductMatcher {

    static final int YOUNG_BUSINESS_MONTHS = 12;

    private static final String NEW_BUSINESS_PRODUCT = "GAMMA_START";

    private static final Map<PurposeDto, List<String>> PRIORITY_BY_PURPOSE = Map.of(
            PurposeDto.WORKING_CAPITAL, List.of("CREDIT_LINE", "LUMP_SUM_LOAN", "OVERDRAFT"),
            PurposeDto.EQUIPMENT, List.of("LUMP_SUM_LOAN", "CREDIT_LINE"),
            PurposeDto.CASH_GAP, List.of("OVERDRAFT", "CREDIT_LINE"),
            PurposeDto.REFINANCING, List.of("LUMP_SUM_LOAN"),
            PurposeDto.STARTUP, List.of(NEW_BUSINESS_PRODUCT));

    record Request(PurposeDto purpose, BigDecimal amount, int term, TermUnitDto termUnit, FundingModeDto fundingMode) {
    }

    record Match(CreditProduct product, List<String> reasons, boolean suitable, boolean requiresSubscription,
                 boolean recommended) {

        boolean fits() {
            return suitable || requiresSubscription;
        }
    }

    List<Match> match(Request request, List<CreditProduct> products, int businessAgeMonths) {
        List<String> priority = priority(request.purpose(), businessAgeMonths);
        List<Match> evaluated = products.stream()
                .map(product -> evaluate(product, request, businessAgeMonths))
                .sorted(Comparator.comparing((Match m) -> !m.fits())
                        .thenComparingInt(m -> m.fits() ? rank(priority, m.product()) : 0)
                        .thenComparingInt(m -> m.product().getSortOrder()))
                .toList();
        if (evaluated.isEmpty() || !evaluated.getFirst().fits()) {
            return evaluated;
        }
        List<Match> result = new ArrayList<>(evaluated);
        Match first = result.getFirst();
        result.set(0, new Match(first.product(), first.reasons(), first.suitable(), first.requiresSubscription(), true));
        return result;
    }

    private static Match evaluate(CreditProduct product, Request request, int businessAgeMonths) {
        List<String> reasons = new ArrayList<>();
        addIfPresent(reasons, ProductRules.amountReason(product, request.amount()));
        ProductRules.TermFit termFit = ProductRules.termFit(product, request.term(), request.termUnit());
        int termReasonPosition = reasons.size();
        if (termFit == ProductRules.TermFit.TOO_SHORT) {
            reasons.add(ProductRules.minTermReason(product));
        } else if (termFit == ProductRules.TermFit.TOO_LONG) {
            reasons.add(ProductRules.maxTermReason(product));
        }
        addIfPresent(reasons, ProductRules.fundingModeReason(product, request.fundingMode()));
        addIfPresent(reasons, ProductRules.businessAgeReason(product, businessAgeMonths));

        boolean otherwiseFits = reasons.isEmpty();
        boolean suitable = otherwiseFits && termFit == ProductRules.TermFit.FITS;
        boolean requiresSubscription = otherwiseFits && termFit == ProductRules.TermFit.FITS_WITH_SUBSCRIPTION;
        if (requiresSubscription) {
            reasons.add(ProductRules.subscriptionReason(product));
        } else if (termFit == ProductRules.TermFit.FITS_WITH_SUBSCRIPTION) {
            // Срок тоже называем, чтобы клиент не упёрся в него после исправления другой причины
            reasons.add(termReasonPosition, ProductRules.maxTermReason(product));
        }
        return new Match(product, List.copyOf(reasons), suitable, requiresSubscription, false);
    }

    private static void addIfPresent(List<String> reasons, String reason) {
        if (reason != null) {
            reasons.add(reason);
        }
    }

    private static List<String> priority(PurposeDto purpose, int businessAgeMonths) {
        List<String> priority = new ArrayList<>(PRIORITY_BY_PURPOSE.getOrDefault(purpose, List.of()));
        if (businessAgeMonths < YOUNG_BUSINESS_MONTHS) {
            priority.remove(NEW_BUSINESS_PRODUCT);
            priority.addFirst(NEW_BUSINESS_PRODUCT);
        }
        return priority;
    }

    private static int rank(List<String> priority, CreditProduct product) {
        int index = priority.indexOf(product.getCode());
        return index >= 0 ? index : priority.size();
    }
}
