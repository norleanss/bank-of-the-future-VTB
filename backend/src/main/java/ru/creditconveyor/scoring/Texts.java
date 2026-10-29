package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;

/**
 * Форматирование чисел и подписи полей в текстах для клиента и андеррайтера.
 */
final class Texts {

    private Texts() {
    }

    /** 1 500 000 ₽, 26 663,5 ₽ */
    static String rubles(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + " ₽";
    }

    /** 23,45% */
    static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }

    static String plural(int count, String one, String few, String many) {
        int mod100 = Math.abs(count) % 100;
        int mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        return mod10 >= 2 && mod10 <= 4 ? few : many;
    }

    static String fieldLabel(String field) {
        return switch (field) {
            case "financials.annualRevenue" -> "выручка за год";
            case "financials.annualExpenses" -> "расходы за год";
            case "financials.bankMonthlyTurnover" -> "среднемесячные обороты по счёту в Гамма-Банке";
            case "financials.monthlyDebtPayment" -> "ежемесячные платежи по кредитам";
            default -> field;
        };
    }

    static String fieldLabels(List<String> fields) {
        return String.join(", ", fields.stream().map(Texts::fieldLabel).toList());
    }
}
