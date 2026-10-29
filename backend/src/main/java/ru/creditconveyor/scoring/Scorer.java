package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.scoring.CreditCase.ExternalData;
import ru.creditconveyor.scoring.CreditCase.Financials;
import ru.creditconveyor.scoring.ScoreResult.FactorResult;
import ru.creditconveyor.scoring.ScoringProperties.FactorScale;
import ru.creditconveyor.scoring.ScoringProperties.Factors;
import ru.creditconveyor.scoring.ScoringProperties.GradeThreshold;

/**
 * Балл — сумма баллов факторов. Финансовый фактор без данных анкеты не считается, а балл нормируется
 * к шкале 0–100: сумма / максимум посчитанных факторов × 100, половина вверх.
 */
final class Scorer {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MONTHS_IN_YEAR = BigDecimal.valueOf(12);

    private final Factors scales;
    private final List<GradeThreshold> grades;

    Scorer(ScoringProperties properties) {
        this.scales = properties.factors();
        this.grades = properties.grades().stream()
                .sorted((a, b) -> Integer.compare(b.minScore(), a.minScore())).toList();
    }

    ScoreResult score(Financials financials, ExternalData external, LocalDate today) {
        List<FactorResult> factors = new ArrayList<>();
        addIfPresent(factors, profitability(financials));
        addIfPresent(factors, debtLoad(financials));
        factors.add(overdueDays(Objects.requireNonNull(external.maxOverdueDays12m())));
        factors.add(taxDebt(Objects.requireNonNull(external.taxDebtAmount())));
        factors.add(businessAge(Objects.requireNonNull(external.registrationDate()), today));
        factors.add(activeCredits(Objects.requireNonNull(external.activeCreditsCount())));

        int sum = factors.stream().mapToInt(FactorResult::points).sum();
        int max = factors.stream().mapToInt(FactorResult::maxPoints).sum();
        int fullMax = fullMax();
        int score = sum;
        if (max < fullMax) {
            score = BigDecimal.valueOf(sum).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(max), 0, RoundingMode.HALF_UP).intValueExact();
            factors.add(new FactorResult(Factor.NORMALIZATION, sum + "/" + max, score - sum, 0,
                    "Финансовые данные не требуются: " + sum + " из " + max + " баллов → " + score + " из 100"));
        }
        return new ScoreResult(score, grade(score), List.copyOf(factors));
    }

    String grade(int score) {
        return grades.stream().filter(g -> score >= g.minScore()).findFirst()
                .orElse(grades.getLast()).grade();
    }

    private int fullMax() {
        return scales.profitability().maxPoints() + scales.debtLoad().maxPoints() + scales.overdueDays().maxPoints()
                + scales.taxDebt().maxPoints() + scales.businessAge().maxPoints() + scales.activeCredits().maxPoints();
    }

    private @Nullable FactorResult profitability(Financials financials) {
        BigDecimal revenue = financials.annualRevenue();
        BigDecimal expenses = financials.annualExpenses();
        if (revenue == null || expenses == null) {
            return null;
        }
        FactorScale scale = scales.profitability();
        if (revenue.signum() == 0) {
            return new FactorResult(Factor.PROFITABILITY, null, scale.otherwise(), scale.maxPoints(),
                    "Выручки за год нет");
        }
        BigDecimal margin = revenue.subtract(expenses).multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        String explanation = expenses.compareTo(revenue) >= 0
                ? "Убыток: расходы за год не меньше выручки"
                : "Рентабельность " + Texts.percent(margin);
        return new FactorResult(Factor.PROFITABILITY, plain(margin), scale.points(margin), scale.maxPoints(), explanation);
    }

    private @Nullable FactorResult debtLoad(Financials financials) {
        BigDecimal revenue = financials.annualRevenue();
        BigDecimal payment = financials.monthlyDebtPayment();
        if (revenue == null || payment == null) {
            return null;
        }
        FactorScale scale = scales.debtLoad();
        if (revenue.signum() == 0) {
            return payment.signum() == 0
                    ? new FactorResult(Factor.DEBT_LOAD, "0", scale.points(BigDecimal.ZERO), scale.maxPoints(),
                            "Платежей по кредитам нет")
                    : new FactorResult(Factor.DEBT_LOAD, null, scale.otherwise(), scale.maxPoints(),
                            "Платежи по кредитам при нулевой выручке");
        }
        BigDecimal load = payment.multiply(MONTHS_IN_YEAR).multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        return new FactorResult(Factor.DEBT_LOAD, plain(load), scale.points(load), scale.maxPoints(),
                "Платежи по кредитам за год — " + Texts.percent(load) + " выручки");
    }

    private FactorResult overdueDays(int days) {
        FactorScale scale = scales.overdueDays();
        String explanation = days == 0
                ? "Просрочек за 12 месяцев нет"
                : "Максимальная просрочка за 12 месяцев — " + days + " " + Texts.plural(days, "день", "дня", "дней");
        return new FactorResult(Factor.OVERDUE_DAYS, String.valueOf(days), scale.points(BigDecimal.valueOf(days)),
                scale.maxPoints(), explanation);
    }

    private FactorResult taxDebt(BigDecimal amount) {
        FactorScale scale = scales.taxDebt();
        String explanation = amount.signum() == 0 ? "Налогового долга нет" : "Налоговый долг " + Texts.rubles(amount);
        return new FactorResult(Factor.TAX_DEBT, amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                scale.points(amount), scale.maxPoints(), explanation);
    }

    private FactorResult businessAge(LocalDate registrationDate, LocalDate today) {
        int months = businessAgeMonths(registrationDate, today);
        FactorScale scale = scales.businessAge();
        return new FactorResult(Factor.BUSINESS_AGE, String.valueOf(months), scale.points(BigDecimal.valueOf(months)),
                scale.maxPoints(), "Бизнесу " + months + " " + Texts.plural(months, "месяц", "месяца", "месяцев"));
    }

    private FactorResult activeCredits(int count) {
        FactorScale scale = scales.activeCredits();
        String explanation = count == 0
                ? "Действующих кредитов нет"
                : count + " " + Texts.plural(count, "действующий кредит", "действующих кредита", "действующих кредитов");
        return new FactorResult(Factor.ACTIVE_CREDITS, String.valueOf(count), scale.points(BigDecimal.valueOf(count)),
                scale.maxPoints(), explanation);
    }

    /** Полных месяцев с регистрации — так же считает возраст бизнеса подбор продукта. */
    static int businessAgeMonths(LocalDate registrationDate, LocalDate today) {
        return (int) Math.max(0, ChronoUnit.MONTHS.between(registrationDate, today));
    }

    private static void addIfPresent(List<FactorResult> factors, @Nullable FactorResult factor) {
        if (factor != null) {
            factors.add(factor);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
