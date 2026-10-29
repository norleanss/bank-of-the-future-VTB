package ru.creditconveyor.product;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.creditconveyor.api.FundingModeDto.LUMP_SUM;
import static ru.creditconveyor.api.FundingModeDto.ON_DEMAND;
import static ru.creditconveyor.api.FundingModeDto.TRANCHES;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.PurposeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Правила подбора на том же каталоге, что в базе: сроки в своих единицах, подписка, причины.
 */
class ProductMatcherTest {

    static final List<CreditProduct> CATALOG = List.of(
            new CreditProduct("LUMP_SUM_LOAN", money("300000"), money("30000000"), TermKindDto.LOAN, TermUnitDto.MONTHS,
                    12, 60, null, List.of(LUMP_SUM), 12, 1,
                    RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("23.50"), new BigDecimal("14.00"), null),
            new CreditProduct("CREDIT_LINE", money("300000"), money("30000000"), TermKindDto.TRANCHE, TermUnitDto.MONTHS,
                    1, 6, 12, List.of(TRANCHES, ON_DEMAND), 12, 2,
                    RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("23.50"), new BigDecimal("14.50"), null),
            new CreditProduct("OVERDRAFT", money("50000"), money("20000000"), TermKindDto.TRANCHE, TermUnitDto.DAYS,
                    1, 60, 90, List.of(ON_DEMAND), 0, 3,
                    RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("26.00"), new BigDecimal("13.00"), new BigDecimal("1.00")),
            new CreditProduct("GAMMA_START", money("50000"), money("1000000"), TermKindDto.TRANCHE, TermUnitDto.MONTHS,
                    1, 6, 12, List.of(TRANCHES, ON_DEMAND), 0, 4,
                    RateUnitDto.PERCENT_PER_DAY, new BigDecimal("0.30"), new BigDecimal("0.15"), null));

    private final ProductMatcher matcher = new ProductMatcher();

    /** Пример из контракта: транш 75 дней есть у овердрафта только с подпиской. */
    @Test
    void overdraftFitsOnlyWithSubscriptionAsInContractExample() {
        List<ProductMatcher.Match> result = match(PurposeDto.CASH_GAP, "1500000.00", 75, TermUnitDto.DAYS, ON_DEMAND, 65);

        assertThat(result).extracting(m -> m.product().getCode())
                .containsExactly("OVERDRAFT", "CREDIT_LINE", "LUMP_SUM_LOAN", "GAMMA_START");
        assertThat(result).extracting(ProductMatcher.Match::suitable).containsExactly(false, true, false, false);
        assertThat(result).extracting(ProductMatcher.Match::requiresSubscription).containsExactly(true, false, false, false);
        assertThat(result).extracting(ProductMatcher.Match::recommended).containsExactly(true, false, false, false);
        assertThat(result.get(0).reasons()).containsExactly(
                "Транш до 60 дней; с подпиской — до 90 дней, ставка от 13% вместо 26%, комиссия 1% от суммы");
        assertThat(result.get(1).reasons()).isEmpty();
        assertThat(result.get(2).reasons())
                .containsExactly("Минимальный срок кредита — 12 месяцев", "Выдаётся только всей суммой сразу");
        assertThat(result.get(3).reasons()).containsExactly("Максимальная сумма — 1 000 000 ₽");
    }

    @Test
    void termsInDifferentUnitsAreComparedInDays() {
        // 2 месяца = 60 дней — овердрафт подходит на базовых условиях
        assertThat(overdraft(match(PurposeDto.CASH_GAP, "500000.00", 2, TermUnitDto.MONTHS, ON_DEMAND, 65)).suitable()).isTrue();
        // 3 месяца = 90 дней — только с подпиской
        assertThat(overdraft(match(PurposeDto.CASH_GAP, "500000.00", 3, TermUnitDto.MONTHS, ON_DEMAND, 65)).requiresSubscription()).isTrue();
        // 91 день — не подходит даже с подпиской
        ProductMatcher.Match tooLong = overdraft(match(PurposeDto.CASH_GAP, "500000.00", 91, TermUnitDto.DAYS, ON_DEMAND, 65));
        assertThat(tooLong.fits()).isFalse();
        assertThat(tooLong.reasons()).containsExactly("Максимальный срок транша — 60 дней, с подпиской — 90 дней");
    }

    /** term × 30 дней не помещается в int — срок всё равно «слишком длинный», а не отрицательный. */
    @Test
    void hugeTermIsTooLongWithoutOverflow() {
        ProductMatcher.Match loan = match(PurposeDto.EQUIPMENT, "500000.00", Integer.MAX_VALUE / 15, TermUnitDto.MONTHS,
                LUMP_SUM, 40).stream().filter(m -> m.product().getCode().equals("LUMP_SUM_LOAN")).findFirst().orElseThrow();

        assertThat(loan.fits()).isFalse();
        assertThat(loan.reasons()).containsExactly("Максимальный срок кредита — 60 месяцев");
    }

    @Test
    void creditLineTrancheOverSixMonthsNeedsSubscription() {
        List<ProductMatcher.Match> result = match(PurposeDto.WORKING_CAPITAL, "2000000.00", 9, TermUnitDto.MONTHS, TRANCHES, 40);

        ProductMatcher.Match creditLine = result.getFirst();
        assertThat(creditLine.product().getCode()).isEqualTo("CREDIT_LINE");
        assertThat(creditLine.requiresSubscription()).isTrue();
        assertThat(creditLine.recommended()).isTrue();
        assertThat(creditLine.reasons()).containsExactly(
                "Транш до 6 месяцев; с подпиской — до 12 месяцев, ставка от 14,5% вместо 23,5%");
    }

    @Test
    void subscriptionDoesNotFixOtherProblems() {
        // Гамма-Старт: сумма больше лимита — подписка не поможет, хотя срок подошёл бы только с ней
        ProductMatcher.Match gammaStart = match(PurposeDto.STARTUP, "2000000.00", 9, TermUnitDto.MONTHS, TRANCHES, 3).stream()
                .filter(m -> m.product().getCode().equals("GAMMA_START")).findFirst().orElseThrow();
        assertThat(gammaStart.fits()).isFalse();
        assertThat(gammaStart.reasons()).containsExactly(
                "Максимальная сумма — 1 000 000 ₽", "Максимальный срок транша — 6 месяцев, с подпиской — 12 месяцев");
    }

    @Test
    void recommendsGammaStartToYoungBusiness() {
        List<ProductMatcher.Match> result = match(PurposeDto.EQUIPMENT, "800000.00", 6, TermUnitDto.MONTHS, TRANCHES, 3);

        assertThat(result.getFirst().product().getCode()).isEqualTo("GAMMA_START");
        assertThat(result.getFirst().suitable()).isTrue();
        assertThat(result.getFirst().recommended()).isTrue();
        assertThat(result).filteredOn(m -> m.product().getCode().equals("CREDIT_LINE")).singleElement()
                .satisfies(m -> assertThat(m.reasons()).containsExactly("Минимальный возраст бизнеса — 12 месяцев"));
    }

    @Test
    void perDayRateIsDescribedPerDay() {
        ProductMatcher.Match gammaStart = match(PurposeDto.STARTUP, "500000.00", 9, TermUnitDto.MONTHS, TRANCHES, 3).getFirst();

        assertThat(gammaStart.requiresSubscription()).isTrue();
        assertThat(gammaStart.reasons()).containsExactly(
                "Транш до 6 месяцев; с подпиской — до 12 месяцев, ставка от 0,15% вместо 0,3% в день");
    }

    @Test
    void nothingFitsMeansNoRecommendation() {
        List<ProductMatcher.Match> result = match(PurposeDto.EQUIPMENT, "50000000.00", 24, TermUnitDto.MONTHS, LUMP_SUM, 3);

        assertThat(result).noneMatch(ProductMatcher.Match::fits);
        assertThat(result).noneMatch(ProductMatcher.Match::recommended);
        assertThat(result).extracting(m -> m.product().getCode())
                .containsExactly("LUMP_SUM_LOAN", "CREDIT_LINE", "OVERDRAFT", "GAMMA_START");
    }

    @Test
    void formatsAmountsTermsAndPercents() {
        assertThat(ProductRules.rubles(new BigDecimal("1500000.50"))).isEqualTo("1 500 000,5 ₽");
        assertThat(ProductRules.term(1, TermUnitDto.MONTHS)).isEqualTo("1 месяц");
        assertThat(ProductRules.term(3, TermUnitDto.MONTHS)).isEqualTo("3 месяца");
        assertThat(ProductRules.term(12, TermUnitDto.MONTHS)).isEqualTo("12 месяцев");
        assertThat(ProductRules.term(21, TermUnitDto.DAYS)).isEqualTo("21 день");
        assertThat(ProductRules.term(60, TermUnitDto.DAYS)).isEqualTo("60 дней");
        assertThat(ProductRules.termUpTo(1, TermUnitDto.MONTHS)).isEqualTo("1 месяца");
        assertThat(ProductRules.termUpTo(90, TermUnitDto.DAYS)).isEqualTo("90 дней");
        assertThat(ProductRules.percent(new BigDecimal("14.50"))).isEqualTo("14,5%");
        assertThat(ProductRules.percent(new BigDecimal("13.00"))).isEqualTo("13%");
    }

    private List<ProductMatcher.Match> match(PurposeDto purpose, String amount, int term, TermUnitDto unit,
                                             FundingModeDto fundingMode, int businessAgeMonths) {
        var request = new ProductMatcher.Request(purpose, new BigDecimal(amount), term, unit, fundingMode);
        return matcher.match(request, CATALOG, businessAgeMonths);
    }

    private static ProductMatcher.Match overdraft(List<ProductMatcher.Match> result) {
        return result.stream().filter(m -> m.product().getCode().equals("OVERDRAFT")).findFirst().orElseThrow();
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
