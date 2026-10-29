package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;

import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Условия продукта для подбора и проверки заявки. Сроки сравниваются в днях, месяц = 30 дней; в {@code long} —
 * огромный срок из запроса не переполняется и честно оказывается «слишком длинным».
 * Методы {@code *Reason} возвращают {@code null}, если условие выполнено.
 */
final class ProductRules {

    static final int DAYS_IN_MONTH = 30;

    enum TermFit {
        FITS,
        FITS_WITH_SUBSCRIPTION,
        TOO_SHORT,
        TOO_LONG
    }

    private ProductRules() {
    }

    static TermFit termFit(CreditProduct product, int term, TermUnitDto unit) {
        long days = toDays(term, unit);
        if (days < toDays(product.getMinTerm(), product.getTermUnit())) {
            return TermFit.TOO_SHORT;
        }
        if (days <= toDays(product.getMaxTerm(), product.getTermUnit())) {
            return TermFit.FITS;
        }
        if (offersSubscription(product) && product.getMaxTermWithSubscription() != null
                && days <= toDays(product.getMaxTermWithSubscription(), product.getTermUnit())) {
            return TermFit.FITS_WITH_SUBSCRIPTION;
        }
        return TermFit.TOO_LONG;
    }

    static boolean offersSubscription(CreditProduct product) {
        return product.getRateWithSubscription() != null;
    }

    static String amountReason(CreditProduct product, BigDecimal amount) {
        if (amount.compareTo(product.getMinAmount()) < 0) {
            return "Минимальная сумма — " + rubles(product.getMinAmount());
        }
        if (amount.compareTo(product.getMaxAmount()) > 0) {
            return "Максимальная сумма — " + rubles(product.getMaxAmount());
        }
        return null;
    }

    static String minTermReason(CreditProduct product) {
        return "Минимальный срок " + termOf(product) + " — " + term(product.getMinTerm(), product.getTermUnit());
    }

    static String maxTermReason(CreditProduct product) {
        String reason = "Максимальный срок " + termOf(product) + " — " + term(product.getMaxTerm(), product.getTermUnit());
        if (offersSubscription(product) && product.getMaxTermWithSubscription() != null) {
            reason += ", с подпиской — " + term(product.getMaxTermWithSubscription(), product.getTermUnit());
        }
        return reason;
    }

    /** «Транш до 60 дней; с подпиской — до 90 дней, ставка от 13% вместо 26%, комиссия 1% от суммы» */
    static String subscriptionReason(CreditProduct product) {
        StringBuilder reason = new StringBuilder()
                .append(product.getTermKind() == TermKindDto.LOAN ? "Срок кредита" : "Транш")
                .append(" до ").append(termUpTo(product.getMaxTerm(), product.getTermUnit()))
                .append("; с подпиской — до ").append(termUpTo(product.getMaxTermWithSubscription(), product.getTermUnit()))
                .append(", ставка от ").append(percent(product.getRateWithSubscription()))
                .append(" вместо ").append(percent(product.getBaseRate()));
        if (product.getRateUnit() == RateUnitDto.PERCENT_PER_DAY) {
            reason.append(" в день");
        }
        if (product.getSubscriptionFeePercent() != null) {
            reason.append(", комиссия ").append(percent(product.getSubscriptionFeePercent())).append(" от суммы");
        }
        return reason.toString();
    }

    static String fundingModeReason(CreditProduct product, FundingModeDto requested) {
        List<FundingModeDto> productModes = product.getFundingModes();
        if (productModes.contains(requested)) {
            return null;
        }
        if (productModes.equals(List.of(FundingModeDto.LUMP_SUM))) {
            return "Выдаётся только всей суммой сразу";
        }
        return switch (requested) {
            case LUMP_SUM -> "Не выдаётся всей суммой сразу";
            case TRANCHES -> "Не выдаётся частями";
            case ON_DEMAND -> "Не выдаётся по мере необходимости";
        };
    }

    static String businessAgeReason(CreditProduct product, int businessAgeMonths) {
        return businessAgeMonths < product.getMinBusinessAgeMonths()
                ? "Минимальный возраст бизнеса — " + term(product.getMinBusinessAgeMonths(), TermUnitDto.MONTHS)
                : null;
    }

    static long toDays(int term, TermUnitDto unit) {
        return unit == TermUnitDto.MONTHS ? (long) term * DAYS_IN_MONTH : term;
    }

    private static String termOf(CreditProduct product) {
        return product.getTermKind() == TermKindDto.LOAN ? "кредита" : "транша";
    }

    static String rubles(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + " ₽";
    }

    static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }

    /** Именительный падеж: 1 месяц, 3 месяца, 12 месяцев; 1 день, 3 дня, 60 дней */
    static String term(int count, TermUnitDto unit) {
        return count + " " + (unit == TermUnitDto.MONTHS
                ? plural(count, "месяц", "месяца", "месяцев")
                : plural(count, "день", "дня", "дней"));
    }

    /** После «до»: до 1 месяца, до 6 месяцев; до 1 дня, до 60 дней */
    static String termUpTo(int count, TermUnitDto unit) {
        boolean one = count % 10 == 1 && count % 100 != 11;
        return count + " " + (unit == TermUnitDto.MONTHS
                ? (one ? "месяца" : "месяцев")
                : (one ? "дня" : "дней"));
    }

    private static String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        return mod10 >= 2 && mod10 <= 4 ? few : many;
    }
}
