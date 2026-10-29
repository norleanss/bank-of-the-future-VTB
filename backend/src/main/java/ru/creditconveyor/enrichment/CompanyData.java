package ru.creditconveyor.enrichment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Профиль компании по ЕГРЮЛ, ФНС и БКИ для решения по заявке.
 *
 * @param companyFound       {@code false} — ЕГРЮЛ ответил, что компании нет
 * @param unavailableSources источники, не ответившие после повторов (EGRUL, FNS, BKI); их данных нет
 */
public record CompanyData(
        boolean companyFound,
        List<String> unavailableSources,
        @Nullable String companyStatus,
        @Nullable LocalDate registrationDate,
        @Nullable BigDecimal taxDebtAmount,
        @Nullable Integer activeCreditsCount,
        @Nullable BigDecimal totalDebtAmount,
        @Nullable Integer maxOverdueDays12m) {
}
