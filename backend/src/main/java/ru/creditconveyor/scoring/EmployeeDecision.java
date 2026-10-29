package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Решение андеррайтера для журнала решений; подтверждение предварительного одобрения — {@link Outcome#APPROVE}.
 *
 * @param offeredAmount   сумма предложения для одобрения и контрпредложения
 * @param requestedFields для {@link Outcome#NEED_INFO} — какие поля анкеты запрошены
 */
public record EmployeeDecision(
        Outcome outcome,
        UUID employeeId,
        @Nullable BigDecimal offeredAmount,
        List<String> reasonCodes,
        List<String> requestedFields,
        @Nullable String comment) {

    public enum Outcome {
        APPROVE,
        COUNTER_OFFER,
        NEED_INFO,
        REJECT
    }
}
