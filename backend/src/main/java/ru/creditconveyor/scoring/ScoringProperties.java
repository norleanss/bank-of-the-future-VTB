package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Значения базовой политики ({@code app.scoring}): шкалы факторов, грейды, пороги, стоп- и мягкие факторы,
 * обязательные поля, лимиты, граница автоодобрения. Правила, которые их применяют, — в {@link CreditPolicy}.
 *
 * @param weakFactorShare    при отказе по баллу называем факторы, набравшие меньше этой доли своего максимума
 * @param rejectBelow        балл ниже — отказ
 * @param reviewBelow        балл ниже (и не ниже {@code rejectBelow}) — ручная проверка
 * @param alternativeProduct предлагаем, если заявленный продукт не подходит по возрасту бизнеса
 * @param requiredFields     продукт → поля анкеты (путь как в {@code ApplicationPatchRequest}); нет записи — полей нет
 * @param preliminaryAbove   итоговая сумма больше — предварительное одобрение с подтверждением андеррайтером
 */
@ConfigurationProperties("app.scoring")
record ScoringProperties(
        Factors factors,
        BigDecimal weakFactorShare,
        List<GradeThreshold> grades,
        int rejectBelow,
        int reviewBelow,
        StopFactors stopFactors,
        String alternativeProduct,
        SoftFactors softFactors,
        Map<String, List<String>> requiredFields,
        Limits limits,
        BigDecimal preliminaryAbove) {

    record Factors(
            FactorScale profitability,
            FactorScale debtLoad,
            FactorScale overdueDays,
            FactorScale taxDebt,
            FactorScale businessAge,
            FactorScale activeCredits) {
    }

    /** Баллы — по первой подходящей полосе, иначе {@code otherwise}. */
    record FactorScale(List<Band> bands, int otherwise) {

        int points(BigDecimal value) {
            return bands.stream().filter(band -> band.when().test(value, band.value()))
                    .findFirst().map(Band::points).orElse(otherwise);
        }

        int maxPoints() {
            return bands.stream().mapToInt(Band::points).reduce(otherwise, Math::max);
        }
    }

    record Band(Comparison when, BigDecimal value, int points) {
    }

    enum Comparison {
        LT, LE, GE, GT;

        boolean test(BigDecimal value, BigDecimal bound) {
            int cmp = value.compareTo(bound);
            return switch (this) {
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                case GE -> cmp >= 0;
                case GT -> cmp > 0;
            };
        }
    }

    record GradeThreshold(String grade, int minScore) {
    }

    /** Просрочка больше {@code maxOverdueDays} и налоговый долг больше {@code maxTaxDebt} — стоп-факторы. */
    record StopFactors(List<String> companyStatuses, int maxOverdueDays, BigDecimal maxTaxDebt) {
    }

    /** Просрочка от {@code overdueDaysFrom} до стоп-порога включительно; {@code loss} — расходы ≥ выручки. */
    record SoftFactors(int overdueDaysFrom, boolean loss) {
    }

    record Limits(
            BigDecimal roundingStep,
            Map<String, LimitMethod> method,
            Map<String, BigDecimal> revenueShareByGrade,
            BigDecimal turnoverShare) {
    }

    enum LimitMethod {
        /** выручка × доля по грейду − долг по БКИ */
        REVENUE,
        /** доля среднемесячных оборотов по счёту */
        TURNOVER,
        /** потолок продукта, без финансов */
        PRODUCT_MAX
    }
}
