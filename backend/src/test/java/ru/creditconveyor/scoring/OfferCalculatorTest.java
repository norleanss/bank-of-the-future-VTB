package ru.creditconveyor.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.creditconveyor.scoring.PolicyTestData.application;
import static ru.creditconveyor.scoring.PolicyTestData.money;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Ставка, комиссия, срок договора и платёж — суммы совпадают с примерами контракта.
 */
class OfferCalculatorTest {

    @Test
    void annuityLoanWithoutSubscription() {
        ProposedOffer offer = offer(application().amount("5000000").term(36, TermUnitDto.MONTHS), "5000000");

        assertThat(offer.rate()).isEqualByComparingTo("23.50");
        assertThat(offer.rateUnit()).isEqualTo(RateUnitDto.PERCENT_PER_YEAR);
        assertThat(offer.repaymentType()).isEqualTo(RepaymentTypeDto.ANNUITY);
        assertThat(offer.monthlyPayment()).isEqualTo(money("194854.03"));
        assertThat(offer.subscription()).isFalse();
        assertThat(offer.subscriptionFee()).isNull();
        assertThat(offer.agreementTermMonths()).isNull();
    }

    @Test
    void overdraftWithSubscriptionHasLowerRateAndOneTimeFee() {
        ProposedOffer offer = offer(application().product("OVERDRAFT").term(75, TermUnitDto.DAYS).repayment(null)
                .subscription(), "800000");

        assertThat(offer.rate()).isEqualByComparingTo("13.00");
        assertThat(offer.repaymentType()).isEqualTo(RepaymentTypeDto.INTEREST_ONLY);
        assertThat(offer.monthlyPayment()).isEqualTo(money("8666.67"));
        assertThat(offer.subscriptionFee()).isEqualTo(money("8000.00"));
        assertThat(offer.agreementTermMonths()).isEqualTo(12);
        assertThat(offer.term()).isEqualTo(75);
        assertThat(offer.termUnit()).isEqualTo(TermUnitDto.DAYS);
    }

    @Test
    void perDayRateIsMultipliedByThirtyDays() {
        // 500 000 × 0,15% × 30 = 22 500
        ProposedOffer offer = offer(application().product("GAMMA_START").term(9, TermUnitDto.MONTHS).subscription(),
                "500000");

        assertThat(offer.rate()).isEqualByComparingTo("0.15");
        assertThat(offer.rateUnit()).isEqualTo(RateUnitDto.PERCENT_PER_DAY);
        assertThat(offer.monthlyPayment()).isEqualTo(money("22500.00"));
        assertThat(offer.subscriptionFee()).isNull();
        assertThat(offer.agreementTermMonths()).isEqualTo(18);
    }

    @Test
    void repaymentTypeFallsBackToProductDefault() {
        assertThat(offer(application().product("CREDIT_LINE").term(6, TermUnitDto.MONTHS).repayment(null), "1000000")
                .repaymentType()).isEqualTo(RepaymentTypeDto.INTEREST_ONLY);
        assertThat(offer(application().product("OVERDRAFT").term(30, TermUnitDto.DAYS)
                .repayment(RepaymentTypeDto.ANNUITY), "500000").repaymentType()).isEqualTo(RepaymentTypeDto.INTEREST_ONLY);

        ProposedOffer annuityLine = offer(application().product("CREDIT_LINE").term(6, TermUnitDto.MONTHS)
                .repayment(RepaymentTypeDto.ANNUITY), "1000000");
        assertThat(annuityLine.repaymentType()).isEqualTo(RepaymentTypeDto.ANNUITY);
        assertThat(annuityLine.monthlyPayment())
                .isEqualTo(OfferCalculator.annuity(money("1000000"), OfferCalculator.monthlyRate(
                        new BigDecimal("23.50"), RateUnitDto.PERCENT_PER_YEAR), 6));
    }

    @ParameterizedTest(name = "{0} {1} → {2} мес.")
    @CsvSource({"12, MONTHS, 12", "1, DAYS, 1", "30, DAYS, 1", "31, DAYS, 2", "75, DAYS, 3"})
    void termInDaysIsRoundedUpToMonths(int term, TermUnitDto unit, int months) {
        assertThat(OfferCalculator.months(term, unit)).isEqualTo(months);
    }

    @Test
    void annuityWithZeroRateSplitsEvenly() {
        assertThat(OfferCalculator.annuity(money("1200000"), BigDecimal.ZERO, 12)).isEqualTo(money("100000"));
    }

    private static ProposedOffer offer(PolicyTestData.Builder builder, String amount) {
        return OfferCalculator.offer(builder.build(), money(amount), false);
    }
}
