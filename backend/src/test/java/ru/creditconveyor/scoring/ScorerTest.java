package ru.creditconveyor.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.creditconveyor.scoring.PolicyTestData.TODAY;
import static ru.creditconveyor.scoring.PolicyTestData.application;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.creditconveyor.scoring.ScoreResult.FactorResult;

/**
 * Полосы факторов и их границы, грейды, нормирование без финансовых данных.
 */
class ScorerTest {

    private final Scorer scorer = new Scorer(PolicyTestData.PROPERTIES);

    @Test
    void reliableCompanyScoresMaximumWithBreakdown() {
        ScoreResult result = score(application());

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.grade()).isEqualTo("A");
        assertThat(result.factors()).extracting(FactorResult::factor, FactorResult::inputValue, FactorResult::points,
                        FactorResult::explanation)
                .containsExactly(
                        tuple(Factor.PROFITABILITY, "30", 25, "Рентабельность 30%"),
                        tuple(Factor.DEBT_LOAD, "3", 20,
                                "Платежи по кредитам за год — 3% выручки"),
                        tuple(Factor.OVERDUE_DAYS, "0", 20, "Просрочек за 12 месяцев нет"),
                        tuple(Factor.TAX_DEBT, "0.00", 10, "Налогового долга нет"),
                        tuple(Factor.BUSINESS_AGE, "65", 15, "Бизнесу 65 месяцев"),
                        tuple(Factor.ACTIVE_CREDITS, "0", 10, "Действующих кредитов нет"));
    }

    /** Выручка 10 000 000 ₽; рентабельность = (выручка − расходы) / выручка. */
    @ParameterizedTest(name = "расходы {0} → рентабельность {1}% → {2} баллов")
    @CsvSource({
            "8000000, 20, 25",
            "8001000, 19.99, 18",
            "9000000, 10, 18",
            "9001000, 9.99, 8",
            "9999000, 0.01, 8",
            "10000000, 0, 0",
            "12000000, -20, 0"})
    void profitabilityBands(String expenses, String margin, int points) {
        FactorResult factor = factor(score(application().revenue("10000000").expenses(expenses)), Factor.PROFITABILITY);

        assertThat(factor.inputValue()).isEqualTo(margin);
        assertThat(factor.points()).isEqualTo(points);
    }

    @Test
    void lossIsExplained() {
        assertThat(factor(score(application().revenue("10000000").expenses("10000000")), Factor.PROFITABILITY)
                .explanation()).isEqualTo("Убыток: расходы за год не меньше выручки");
    }

    /** Выручка 12 000 000 ₽; нагрузка = платёж × 12 / выручка. */
    @ParameterizedTest(name = "платёж {0} → нагрузка {1}% → {2} баллов")
    @CsvSource({
            "0, 0, 20",
            "99000, 9.9, 20",
            "100000, 10, 12",
            "249000, 24.9, 12",
            "250000, 25, 4",
            "400000, 40, 4",
            "401000, 40.1, 0"})
    void debtLoadBands(String payment, String load, int points) {
        FactorResult factor = factor(score(application().revenue("12000000").expenses("6000000").debtPayment(payment)),
                Factor.DEBT_LOAD);

        assertThat(factor.inputValue()).isEqualTo(load);
        assertThat(factor.points()).isEqualTo(points);
    }

    @ParameterizedTest(name = "просрочка {0} дн. → {1} баллов")
    @CsvSource({"0, 20", "1, 10", "30, 10", "31, 0", "60, 0"})
    void overdueBands(int days, int points) {
        assertThat(factor(score(application().overdueDays(days)), Factor.OVERDUE_DAYS).points()).isEqualTo(points);
    }

    @ParameterizedTest(name = "налоговый долг {0} → {1} баллов")
    @CsvSource({"0, 10", "0.01, 5", "50000, 5", "50000.01, 0", "300000, 0"})
    void taxDebtBands(String debt, int points) {
        assertThat(factor(score(application().taxDebt(debt)), Factor.TAX_DEBT).points()).isEqualTo(points);
    }

    @ParameterizedTest(name = "регистрация {0} → {1} мес. → {2} баллов")
    @CsvSource({
            "2023-11-01, 36, 15",
            "2023-11-02, 35, 10",
            "2025-11-01, 12, 10",
            "2025-11-02, 11, 5",
            "2026-08-01, 3, 5",
            "2026-08-02, 2, 0"})
    void businessAgeBands(LocalDate registered, String months, int points) {
        FactorResult factor = factor(score(application().registered(registered)), Factor.BUSINESS_AGE);

        assertThat(factor.inputValue()).isEqualTo(months);
        assertThat(factor.points()).isEqualTo(points);
    }

    @ParameterizedTest(name = "{0} кредитов → {1} баллов")
    @CsvSource({"0, 10", "1, 10", "2, 5", "3, 0", "7, 0"})
    void activeCreditsBands(int count, int points) {
        assertThat(factor(score(application().credits(count, "0")), Factor.ACTIVE_CREDITS).points()).isEqualTo(points);
    }

    @ParameterizedTest(name = "балл {0} → грейд {1}")
    @CsvSource({"100, A", "85, A", "84, B", "70, B", "69, C", "55, C", "54, D", "40, D", "39, E", "0, E"})
    void grades(int score, String grade) {
        assertThat(scorer.grade(score)).isEqualTo(grade);
    }

    /** Гамма-Старт без анкеты: 5 + 20 + 10 + 10 = 45 из 55 → 45 × 100 / 55 = 81,8 → 82. */
    @Test
    void scoreWithoutFinancialsIsNormalized() {
        ScoreResult result = score(application().product("GAMMA_START").noFinancials()
                .registered(LocalDate.of(2026, 7, 15)));

        assertThat(result.score()).isEqualTo(82);
        assertThat(result.grade()).isEqualTo("B");
        assertThat(result.factors()).extracting(FactorResult::factor).containsExactly(
                Factor.OVERDUE_DAYS, Factor.TAX_DEBT, Factor.BUSINESS_AGE, Factor.ACTIVE_CREDITS, Factor.NORMALIZATION);
        FactorResult normalization = factor(result, Factor.NORMALIZATION);
        assertThat(normalization.inputValue()).isEqualTo("45/55");
        assertThat(normalization.points()).isEqualTo(37);
        assertThat(normalization.explanation()).isEqualTo("Финансовые данные не требуются: 45 из 55 баллов → 82 из 100");
    }

    @ParameterizedTest(name = "{0} из 55 → {1}")
    @CsvSource({"0, 0", "35, 64", "40, 73", "55, 100"})
    void normalizationRoundsHalfUp(int sum, int expected) {
        // 55 = 20 + 10 + 15 + 10: собираем нужную сумму из нефинансовых факторов
        var builder = application().product("GAMMA_START").noFinancials();
        switch (sum) {
            case 0 -> builder.overdueDays(45).taxDebt("100000").registered(LocalDate.of(2026, 10, 1)).credits(3, "0");
            case 35 -> builder.overdueDays(15).credits(3, "0");
            case 40 -> builder.registered(LocalDate.of(2026, 5, 1)).credits(2, "0");
            default -> {
            }
        }
        ScoreResult result = score(builder);

        assertThat(result.factors().stream().filter(f -> f.factor() != Factor.NORMALIZATION)
                .mapToInt(FactorResult::points).sum()).isEqualTo(sum);
        assertThat(result.score()).isEqualTo(expected);
        assertThat(result.factors().stream().mapToInt(FactorResult::points).sum()).isEqualTo(expected);
    }

    @Test
    void onlyAvailableFinancialFactorIsCounted() {
        // Гамма-Старт с выручкой и расходами, без платежей по кредитам: максимум 25 + 55 = 80
        ScoreResult result = score(application().product("GAMMA_START").debtPayment(null).turnover(null));

        assertThat(result.factors()).extracting(FactorResult::factor).doesNotContain(Factor.DEBT_LOAD);
        assertThat(factor(result, Factor.NORMALIZATION).inputValue()).isEqualTo("80/80");
        assertThat(result.score()).isEqualTo(100);
    }

    private ScoreResult score(PolicyTestData.Builder builder) {
        CreditCase credit = builder.build();
        return scorer.score(credit.financials(), credit.external(), TODAY);
    }

    private static FactorResult factor(ScoreResult result, Factor factor) {
        return result.factors().stream().filter(f -> f.factor() == factor).findFirst().orElseThrow();
    }
}
