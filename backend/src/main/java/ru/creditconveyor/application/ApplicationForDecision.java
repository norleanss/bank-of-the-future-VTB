package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Данные заявки для решения политики. Поля выбора продукта при отправке обязательны, финансы — нет.
 */
public record ApplicationForDecision(
        UUID id,
        UUID companyId,
        ApplicationStatus status,
        int round,
        String productCode,
        BigDecimal requestedAmount,
        int term,
        TermUnitDto termUnit,
        @Nullable RepaymentTypeDto repaymentType,
        boolean subscription,
        @Nullable BigDecimal annualRevenue,
        @Nullable BigDecimal annualExpenses,
        @Nullable BigDecimal bankMonthlyTurnover,
        @Nullable BigDecimal monthlyDebtPayment) {
}
