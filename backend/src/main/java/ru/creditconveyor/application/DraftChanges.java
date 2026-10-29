package ru.creditconveyor.application;

import java.math.BigDecimal;

record DraftChanges(
        FieldChange<String> productCode,
        FieldChange<String> purpose,
        FieldChange<BigDecimal> requestedAmount,
        FieldChange<Integer> term,
        FieldChange<String> termUnit,
        FieldChange<String> fundingMode,
        FieldChange<String> repaymentType,
        FieldChange<BigDecimal> annualRevenue,
        FieldChange<BigDecimal> annualExpenses,
        FieldChange<BigDecimal> bankMonthlyTurnover,
        FieldChange<BigDecimal> monthlyDebtPayment,
        FieldChange<Boolean> collateral,
        FieldChange<Boolean> subscription) {
}
