package ru.creditconveyor.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.creditconveyor.scoring.PolicyTestData.TODAY;
import static ru.creditconveyor.scoring.PolicyTestData.application;
import static ru.creditconveyor.scoring.PolicyTestData.money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.scoring.Decision.Outcome;

/**
 * Порядок проверок политики, стоп- и мягкие факторы, пороги балла, лимит, граница N.
 */
class CreditPolicyTest {

    private final CreditPolicy policy = new CreditPolicy(PolicyTestData.PROPERTIES);

    @Test
    void reliableCompanyWithinLimitIsApprovedAutomatically() {
        Decision decision = decide(application());

        assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
        assertThat(decision.reasons()).isEmpty();
        assertThat(decision.scoring().score()).isEqualTo(100);
        assertThat(decision.limit()).isEqualTo(money("10000000"));
        assertThat(decision.offer().amount()).isEqualTo(money("500000"));
        assertThat(decision.offer().preliminary()).isFalse();
    }

    @Nested
    class OrderOfChecks {

        @Test
        void companyNotFoundIsRejectedBeforeAnythingElse() {
            Decision decision = decide(application().companyNotFound().noFinancials());

            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(codes(decision)).containsExactly("COMPANY_NOT_FOUND");
            assertThat(decision.scoring()).isNull();
        }

        @Test
        void unavailableSourceGoesToManualReviewEvenWithStopFactor() {
            Decision decision = decide(application().unavailable("BKI").status("LIQUIDATING"));

            assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
            assertThat(codes(decision)).containsExactly("SOURCE_UNAVAILABLE");
            assertThat(decision.scoring()).isNull();
        }

        @Test
        void stopFactorWinsOverMissingFields() {
            Decision decision = decide(application().status("LIQUIDATING").noFinancials());

            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(codes(decision)).containsExactly("COMPANY_LIQUIDATING");
        }

        @Test
        void missingFieldsWinOverLowScore() {
            Decision decision = decide(application().revenue(null).overdueDays(45).taxDebt("100000").credits(5, "0"));

            assertThat(decision.outcome()).isEqualTo(Outcome.NEED_INFO);
            assertThat(decision.scoring()).isNull();
        }

        @Test
        void lowScoreWinsOverSoftFactor() {
            // 0 + 4 + 0 + 0 + 0 + 0: убыток и просрочка 45 дней — мягкие, но балл ниже 40
            Decision decision = decide(application().product("GAMMA_START").revenue("10000000").expenses("11000000")
                    .debtPayment("300000").overdueDays(45).taxDebt("100000").registered(LocalDate.of(2026, 10, 1)).credits(3, "0"));

            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(codes(decision)).first().isEqualTo("SCORE_LOW");
        }
    }

    @Nested
    class ProductEligibility {

        @Test
        void loanNeedsTwelveMonthsOfBusiness() {
            assertThat(decide(application().registered(LocalDate.of(2025, 11, 1))).outcome())
                    .isEqualTo(Outcome.APPROVE);

            Decision decision = decide(application().registered(LocalDate.of(2025, 11, 2)));
            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(decision.reasons()).containsExactly(new Reason("PRODUCT_NOT_ELIGIBLE",
                    "Продукт «Кредит одной суммой» доступен бизнесу от 12 месяцев",
                    "Подайте заявку на «Гамма-Старт» — он для нового бизнеса и без финансовых документов"));
            assertThat(decision.scoring()).isNull();
        }

        @Test
        void alternativeIsNotSuggestedWhenAmountDoesNotFit() {
            Decision decision = decide(application().product("CREDIT_LINE").amount("2000000")
                    .term(6, TermUnitDto.MONTHS).registered(LocalDate.of(2026, 7, 15)));

            assertThat(decision.reasons()).containsExactly(new Reason("PRODUCT_NOT_ELIGIBLE",
                    "Продукт «Возобновляемая кредитная линия» доступен бизнесу от 12 месяцев",
                    "Подайте заявку, когда бизнесу исполнится 12 месяцев"));
        }

        @Test
        void checkedAfterStopFactorsAndBeforeMissingFields() {
            assertThat(codes(decide(application().registered(LocalDate.of(2026, 7, 15)).status("LIQUIDATING"))))
                    .containsExactly("COMPANY_LIQUIDATING");
            assertThat(codes(decide(application().registered(LocalDate.of(2026, 7, 15)).noFinancials())))
                    .containsExactly("PRODUCT_NOT_ELIGIBLE");
        }

        @Test
        void productsWithoutAgeLimitAcceptNewBusiness() {
            assertThat(decide(application().product("OVERDRAFT").term(30, TermUnitDto.DAYS).repayment(null)
                    .registered(LocalDate.of(2026, 10, 20))).outcome()).isNotEqualTo(Outcome.REJECT);
        }
    }

    @Nested
    class StopFactors {

        @ParameterizedTest
        @CsvSource({"LIQUIDATING, COMPANY_LIQUIDATING", "LIQUIDATED, COMPANY_LIQUIDATED"})
        void liquidationIsStopFactor(String status, String code) {
            Decision decision = decide(application().status(status));

            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(codes(decision)).containsExactly(code);
        }

        @Test
        void overdueAboveSixtyDaysIsStopFactor() {
            assertThat(decide(application().overdueDays(60)).outcome()).isEqualTo(Outcome.MANUAL_REVIEW);

            Decision decision = decide(application().overdueDays(61));
            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(decision.reasons()).containsExactly(
                    new Reason("OVERDUE_GT_60", "Есть просрочка по кредиту больше 60 дней",
                            "Погасите просрочку и подайте заявку снова через 3 месяца"));
        }

        @Test
        void taxDebtAboveThresholdIsStopFactor() {
            assertThat(decide(application().taxDebt("300000")).outcome()).isNotEqualTo(Outcome.REJECT);

            Decision decision = decide(application().taxDebt("300000.01"));
            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(decision.reasons()).containsExactly(
                    new Reason("TAX_DEBT_HIGH", "Налоговая задолженность больше 300 000 ₽",
                            "Погасите налоговую задолженность — сумму можно проверить в личном кабинете ФНС — и подайте заявку снова"));
        }

        @Test
        void allStopFactorsAreReported() {
            Decision decision = decide(application().status("LIQUIDATED").overdueDays(95).taxDebt("500000"));

            assertThat(codes(decision)).containsExactly("COMPANY_LIQUIDATED", "OVERDUE_GT_60", "TAX_DEBT_HIGH");
        }
    }

    @Nested
    class SoftFactors {

        @Test
        void overdueThirtyOneToSixtyDaysGoesToManualReview() {
            assertThat(decide(application().overdueDays(30)).outcome()).isEqualTo(Outcome.APPROVE);

            for (int days : new int[] {31, 60}) {
                Decision decision = decide(application().overdueDays(days));
                assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
                assertThat(decision.reasons()).containsExactly(
                        new Reason("OVERDUE_31_60", "Была просрочка по кредиту от 31 до 60 дней"));
                assertThat(decision.scoring()).isNotNull();
            }
        }

        @Test
        void lossGoesToManualReviewEvenWithHighScore() {
            // 0 + 20 + 20 + 10 + 15 + 10 = 75
            Decision decision = decide(application().revenue("10000000").expenses("10000000").debtPayment("10000"));

            assertThat(decision.scoring().score()).isEqualTo(75);
            assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
            assertThat(codes(decision)).containsExactly("LOSS_MAKING");
        }

        @Test
        void greyZoneAndSoftFactorsAreReportedTogether() {
            // 18 + 4 + 0 + 10 + 15 + 10 = 57
            Decision decision = decide(application().revenue("10000000").expenses("8500000").debtPayment("300000")
                    .overdueDays(40));

            assertThat(decision.scoring().score()).isEqualTo(57);
            assertThat(codes(decision)).containsExactly("SCORE_GREY_ZONE", "OVERDUE_31_60");
        }
    }

    @Nested
    class ScoreThresholds {

        /** 25 + 4 + 10 + 0 + 0 + 0 = 39: слабые факторы (меньше половины максимума) называются причинами. */
        @Test
        void scoreBelowFortyIsRejectedWithWeakFactors() {
            Decision decision = decide(scored().registered(LocalDate.of(2026, 9, 1)));

            assertThat(decision.scoring().score()).isEqualTo(39);
            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(codes(decision)).containsExactly(
                    "SCORE_LOW", "DEBT_LOAD_HIGH", "TAX_DEBT", "YOUNG_BUSINESS", "MANY_ACTIVE_CREDITS");
        }

        /** 25 + 0 + 10 + 0 + 5 + 0 = 40. */
        @Test
        void scoreFortyGoesToManualReview() {
            Decision decision = decide(scored().debtPayment("400000").registered(LocalDate.of(2026, 5, 1)));

            assertThat(decision.scoring().score()).isEqualTo(40);
            assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
            assertThat(codes(decision)).containsExactly("SCORE_GREY_ZONE");
        }

        /** 25 + 4 + 20 + 10 + 10 + 0 = 69. */
        @Test
        void scoreSixtyNineGoesToManualReview() {
            Decision decision = decide(application().revenue("10000000").expenses("7000000").debtPayment("300000")
                    .registered(LocalDate.of(2024, 11, 1)).credits(3, "0"));

            assertThat(decision.scoring().score()).isEqualTo(69);
            assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
        }

        /** 25 + 20 + 10 + 5 + 10 + 0 = 70, грейд B. */
        @Test
        void scoreSeventyIsApproved() {
            Decision decision = decide(application().revenue("10000000").expenses("7000000").debtPayment("50000")
                    .overdueDays(15).taxDebt("40000").registered(LocalDate.of(2024, 11, 1)).credits(3, "0"));

            assertThat(decision.scoring().score()).isEqualTo(70);
            assertThat(decision.scoring().grade()).isEqualTo("B");
            assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
        }

        private PolicyTestData.Builder scored() {
            // Гамма-Старт доступен любому возрасту бизнеса; с анкетой считаются все шесть факторов
            return application().product("GAMMA_START").revenue("10000000").expenses("7000000").debtPayment("300000")
                    .overdueDays(15).taxDebt("100000").credits(3, "0");
        }
    }

    @Nested
    class RequiredFields {

        @Test
        void loanNeedsRevenueExpensesAndDebtPayments() {
            Decision decision = decide(application().noFinancials());

            assertThat(decision.outcome()).isEqualTo(Outcome.NEED_INFO);
            assertThat(decision.missingFields()).containsExactly(
                    "financials.annualRevenue", "financials.annualExpenses", "financials.monthlyDebtPayment");
            assertThat(decision.reasons()).containsExactly(new Reason("MISSING_FIELDS",
                    "Укажите в анкете: выручка за год, расходы за год, ежемесячные платежи по кредитам"));
        }

        @Test
        void overdraftAlsoNeedsTurnover() {
            Decision decision = decide(application().product("OVERDRAFT").term(30, TermUnitDto.DAYS)
                    .turnover(null));

            assertThat(decision.outcome()).isEqualTo(Outcome.NEED_INFO);
            assertThat(decision.missingFields()).containsExactly("financials.bankMonthlyTurnover");
        }

        @Test
        void gammaStartNeedsNoFinancialsAndUsesNormalizedScore() {
            Decision decision = decide(application().product("GAMMA_START").amount("500000").noFinancials()
                    .registered(LocalDate.of(2026, 7, 15)).term(6, TermUnitDto.MONTHS));

            assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
            assertThat(decision.scoring().score()).isEqualTo(82);
            assertThat(decision.limit()).isEqualTo(money("1000000"));
        }

        @Test
        void unknownRequiredFieldStopsStartup() {
            var p = PolicyTestData.PROPERTIES;
            var broken = new ScoringProperties(p.factors(), p.weakFactorShare(), p.grades(), p.rejectBelow(),
                    p.reviewBelow(), p.stopFactors(), p.alternativeProduct(), p.softFactors(),
                    Map.of("LUMP_SUM_LOAN", List.of("financials.revenue")), p.limits(), p.preliminaryAbove());

            assertThatThrownBy(() -> new CreditPolicy(broken)).hasMessageContaining("financials.revenue");
        }

        @Test
        void limitWithoutItsRequiredFieldStopsStartup() {
            var p = PolicyTestData.PROPERTIES;
            var broken = new ScoringProperties(p.factors(), p.weakFactorShare(), p.grades(), p.rejectBelow(),
                    p.reviewBelow(), p.stopFactors(), p.alternativeProduct(), p.softFactors(), Map.of(), p.limits(), p.preliminaryAbove());

            assertThatThrownBy(() -> new CreditPolicy(broken)).hasMessageContaining("required-fields");
        }
    }

    @Nested
    class Limits {

        @Test
        void requestAboveLimitGetsCounterOfferForLimit() {
            // 20 000 000 × 0,5 − 3 000 000 = 7 000 000; два кредита — 5 баллов, балл 95 (A)
            Decision decision = decide(application().amount("8000000").credits(2, "3000000"));

            assertThat(decision.outcome()).isEqualTo(Outcome.COUNTER_OFFER);
            assertThat(decision.limit()).isEqualTo(money("7000000"));
            assertThat(decision.offer().amount()).isEqualTo(money("7000000"));
            assertThat(decision.offer().productCode()).isEqualTo("LUMP_SUM_LOAN");
            assertThat(decision.offer().term()).isEqualTo(24);
            assertThat(decision.offer().preliminary()).isTrue();
            assertThat(decision.reasons()).containsExactly(new Reason("AMOUNT_ABOVE_LIMIT",
                    "Запрошенная сумма больше доступного лимита. Можем предложить 7 000 000 ₽",
                    "Примите предложение на доступную сумму или подайте новую заявку, когда вырастет выручка"));
        }

        @Test
        void gradeBUsesSmallerRevenueShare() {
            // Балл 70 (B): 10 000 000 × 0,4 = 4 000 000
            Decision decision = decide(application().amount("5000000").revenue("10000000").expenses("7000000")
                    .debtPayment("50000").overdueDays(15).taxDebt("40000").registered(LocalDate.of(2024, 11, 1))
                    .credits(3, "0"));

            assertThat(decision.scoring().grade()).isEqualTo("B");
            assertThat(decision.limit()).isEqualTo(money("4000000"));
            assertThat(decision.outcome()).isEqualTo(Outcome.COUNTER_OFFER);
        }

        @Test
        void limitIsRoundedDownToTenThousands() {
            // 1 234 567 × 0,5 = 617 283,50 → 610 000
            Decision decision = decide(application().amount("700000").revenue("1234567").expenses("800000")
                    .debtPayment("1000"));

            assertThat(decision.limit()).isEqualTo(money("610000"));
            assertThat(decision.offer().amount()).isEqualTo(money("610000"));
        }

        @Test
        void limitIsCappedByProductMaximum() {
            Decision decision = decide(application().amount("30000000").revenue("100000000").expenses("70000000")
                    .debtPayment("100000"));

            assertThat(decision.limit()).isEqualTo(money("30000000"));
            assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
        }

        @ParameterizedTest(name = "долг по БКИ {0} → лимит {1}")
        @CsvSource({"200000, 300000", "200001, 290000", "400000, 100000", "900000, 0"})
        void limitBelowProductMinimumIsRejected(String totalDebt, String limit) {
            // 1 000 000 × 0,5 − долг; минимум кредита одной суммой — 300 000
            Decision decision = decide(application().amount("300000").revenue("1000000").expenses("700000")
                    .debtPayment("5000").credits(1, totalDebt));

            assertThat(decision.limit()).isEqualTo(money(limit));
            if (decision.limit().compareTo(money("300000")) >= 0) {
                assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
            } else {
                assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
                assertThat(decision.reasons()).containsExactly(new Reason("INSUFFICIENT_FINANCES",
                        "Финансовых показателей недостаточно для минимальной суммы продукта — 300 000 ₽",
                        "Попробуйте продукт с меньшей минимальной суммой — «Овердрафт для бизнеса» или «Гамма-Старт»"));
                assertThat(decision.offer()).isNull();
            }
        }

        @Test
        void overdraftLimitIsHalfOfMonthlyTurnover() {
            Decision decision = decide(application().product("OVERDRAFT").amount("800000")
                    .term(30, TermUnitDto.DAYS).repayment(null).turnover("1500000"));

            assertThat(decision.limit()).isEqualTo(money("750000"));
            assertThat(decision.outcome()).isEqualTo(Outcome.COUNTER_OFFER);
            assertThat(decision.offer().preliminary()).isFalse();
        }

        /** Совет — только продукты с меньшим минимумом, кроме самого продукта заявки. */
        @Test
        void insufficientOverdraftDoesNotSuggestOverdraft() {
            Decision decision = decide(application().product("OVERDRAFT").amount("800000")
                    .term(30, TermUnitDto.DAYS).repayment(null).turnover("60000"));

            assertThat(decision.outcome()).isEqualTo(Outcome.REJECT);
            assertThat(decision.reasons()).extracting(Reason::code).containsExactly("INSUFFICIENT_FINANCES");
            assertThat(decision.reasons().getFirst().advice())
                    .isEqualTo("Подайте заявку снова, когда вырастут выручка или обороты по счёту");
        }
    }

    @Nested
    class PreliminaryApproval {

        @ParameterizedTest(name = "{0} → preliminary {1}")
        @CsvSource({"1000000.00, false", "1000000.01, true", "5000000.00, true"})
        void amountAboveNIsPreliminary(String amount, boolean preliminary) {
            Decision decision = decide(application().amount(amount));

            assertThat(decision.outcome()).isEqualTo(Outcome.APPROVE);
            assertThat(decision.offer().preliminary()).isEqualTo(preliminary);
        }

        @Test
        void zeroNSendsEveryApprovalToUnderwriter() {
            var p = PolicyTestData.PROPERTIES;
            var everything = new CreditPolicy(new ScoringProperties(p.factors(), p.weakFactorShare(), p.grades(),
                    p.rejectBelow(), p.reviewBelow(), p.stopFactors(), p.alternativeProduct(), p.softFactors(),
                    p.requiredFields(), p.limits(),
                    BigDecimal.ZERO));

            assertThat(everything.decide(application().amount("300000").build(), TODAY).offer().preliminary()).isTrue();
        }
    }

    private Decision decide(PolicyTestData.Builder builder) {
        return policy.decide(builder.build(), TODAY);
    }

    private static List<String> codes(Decision decision) {
        return decision.reasons().stream().map(Reason::code).toList();
    }
}
