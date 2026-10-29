package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.product.ProductTerms;

/**
 * Всё, по чему принимается решение: заявка, продукт и данные внешних источников.
 *
 * @param alternative продукт, который предлагаем, если заявленный не подходит по возрасту бизнеса
 * @param catalog     действующие продукты — для советов клиенту
 */
record CreditCase(
        ProductTerms product,
        @Nullable ProductTerms alternative,
        List<ProductTerms> catalog,
        BigDecimal requestedAmount,
        int term,
        TermUnitDto termUnit,
        @Nullable RepaymentTypeDto repaymentType,
        boolean subscription,
        Financials financials,
        ExternalData external) {

    /** Финансы из анкеты; не заполненное клиентом — {@code null}. */
    record Financials(
            @Nullable BigDecimal annualRevenue,
            @Nullable BigDecimal annualExpenses,
            @Nullable BigDecimal bankMonthlyTurnover,
            @Nullable BigDecimal monthlyDebtPayment) {

        static final List<String> FIELDS = List.of(
                "financials.annualRevenue", "financials.annualExpenses",
                "financials.bankMonthlyTurnover", "financials.monthlyDebtPayment");

        @Nullable BigDecimal value(String field) {
            return switch (field) {
                case "financials.annualRevenue" -> annualRevenue;
                case "financials.annualExpenses" -> annualExpenses;
                case "financials.bankMonthlyTurnover" -> bankMonthlyTurnover;
                case "financials.monthlyDebtPayment" -> monthlyDebtPayment;
                default -> throw new IllegalArgumentException("Неизвестное поле анкеты: " + field);
            };
        }
    }

    /**
     * Данные ЕГРЮЛ, ФНС и БКИ. Если компания найдена и все источники ответили, поля заполнены.
     *
     * @param unavailableSources источники, не ответившие после повторов
     */
    record ExternalData(
            boolean companyFound,
            List<String> unavailableSources,
            @Nullable String companyStatus,
            @Nullable LocalDate registrationDate,
            @Nullable BigDecimal taxDebtAmount,
            @Nullable Integer activeCreditsCount,
            @Nullable BigDecimal totalDebtAmount,
            @Nullable Integer maxOverdueDays12m) {

        static ExternalData companyNotFound() {
            return new ExternalData(false, List.of(), null, null, null, null, null, null);
        }
    }
}
