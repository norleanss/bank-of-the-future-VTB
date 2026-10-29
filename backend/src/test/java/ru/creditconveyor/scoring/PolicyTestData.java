package ru.creditconveyor.scoring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;
import ru.creditconveyor.product.ProductTerms;
import ru.creditconveyor.scoring.CreditCase.ExternalData;
import ru.creditconveyor.scoring.CreditCase.Financials;

/**
 * Значения политики из {@code application.yml} (без Spring-контекста), каталог как в миграциях и заявка-конструктор.
 * По умолчанию — надёжная компания и кредит одной суммой на 500 000 ₽: балл 100, автоодобрение.
 */
final class PolicyTestData {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    static final ScoringProperties PROPERTIES = bindApplicationYml();

    static final Map<String, ProductTerms> CATALOG = Map.of(
            "LUMP_SUM_LOAN", new ProductTerms("LUMP_SUM_LOAN", "Кредит одной суммой", TermKindDto.LOAN, money("300000"),
                    money("30000000"), null, RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("23.50"),
                    new BigDecimal("14.00"), null, List.of(RepaymentTypeDto.ANNUITY), 12),
            "CREDIT_LINE", new ProductTerms("CREDIT_LINE", "Возобновляемая кредитная линия", TermKindDto.TRANCHE,
                    money("300000"),
                    money("30000000"), 18, RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("23.50"),
                    new BigDecimal("14.50"), null, List.of(RepaymentTypeDto.INTEREST_ONLY, RepaymentTypeDto.ANNUITY), 12),
            "OVERDRAFT", new ProductTerms("OVERDRAFT", "Овердрафт для бизнеса", TermKindDto.TRANCHE, money("50000"),
                    money("20000000"),
                    12, RateUnitDto.PERCENT_PER_YEAR, new BigDecimal("26.00"), new BigDecimal("13.00"),
                    new BigDecimal("1.00"), List.of(RepaymentTypeDto.INTEREST_ONLY), 0),
            "GAMMA_START", new ProductTerms("GAMMA_START", "Гамма-Старт", TermKindDto.TRANCHE, money("50000"), money("1000000"), 18,
                    RateUnitDto.PERCENT_PER_DAY, new BigDecimal("0.30"), new BigDecimal("0.15"), null,
                    List.of(RepaymentTypeDto.INTEREST_ONLY), 0));

    private PolicyTestData() {
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    static Builder application() {
        return new Builder();
    }

    private static ScoringProperties bindApplicationYml() {
        try {
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                    .bind("app.scoring", ScoringProperties.class).get();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class Builder {
        private String product = "LUMP_SUM_LOAN";
        private BigDecimal amount = money("500000");
        private int term = 24;
        private TermUnitDto termUnit = TermUnitDto.MONTHS;
        private @Nullable RepaymentTypeDto repaymentType = RepaymentTypeDto.ANNUITY;
        private boolean subscription;
        // Рентабельность 30%, платежи по кредитам 3% выручки
        private @Nullable BigDecimal revenue = money("20000000");
        private @Nullable BigDecimal expenses = money("14000000");
        private @Nullable BigDecimal turnover = money("1500000");
        private @Nullable BigDecimal debtPayment = money("50000");
        private boolean companyFound = true;
        private List<String> unavailable = List.of();
        private String status = "ACTIVE";
        private LocalDate registered = LocalDate.of(2021, 5, 14);
        private BigDecimal taxDebt = money("0");
        private int credits;
        private BigDecimal totalDebt = money("0");
        private int overdueDays;

        Builder product(String code) {
            this.product = code;
            return this;
        }

        Builder amount(String value) {
            this.amount = money(value);
            return this;
        }

        Builder term(int term, TermUnitDto unit) {
            this.term = term;
            this.termUnit = unit;
            return this;
        }

        Builder repayment(@Nullable RepaymentTypeDto type) {
            this.repaymentType = type;
            return this;
        }

        Builder subscription() {
            this.subscription = true;
            return this;
        }

        Builder revenue(@Nullable String value) {
            this.revenue = value == null ? null : money(value);
            return this;
        }

        Builder expenses(@Nullable String value) {
            this.expenses = value == null ? null : money(value);
            return this;
        }

        Builder turnover(@Nullable String value) {
            this.turnover = value == null ? null : money(value);
            return this;
        }

        Builder debtPayment(@Nullable String value) {
            this.debtPayment = value == null ? null : money(value);
            return this;
        }

        Builder noFinancials() {
            return revenue(null).expenses(null).turnover(null).debtPayment(null);
        }

        Builder companyNotFound() {
            this.companyFound = false;
            return this;
        }

        Builder unavailable(String... sources) {
            this.unavailable = List.of(sources);
            return this;
        }

        Builder status(String status) {
            this.status = status;
            return this;
        }

        Builder registered(LocalDate date) {
            this.registered = date;
            return this;
        }

        Builder taxDebt(String value) {
            this.taxDebt = money(value);
            return this;
        }

        Builder credits(int count, String totalDebt) {
            this.credits = count;
            this.totalDebt = money(totalDebt);
            return this;
        }

        Builder overdueDays(int days) {
            this.overdueDays = days;
            return this;
        }

        CreditCase build() {
            ExternalData external = companyFound
                    ? new ExternalData(true, unavailable, status, registered, taxDebt, credits, totalDebt, overdueDays)
                    : ExternalData.companyNotFound();
            return new CreditCase(CATALOG.get(product), CATALOG.get(PROPERTIES.alternativeProduct()),
                    Stream.of("LUMP_SUM_LOAN", "CREDIT_LINE", "OVERDRAFT", "GAMMA_START").map(CATALOG::get).toList(), amount, term, termUnit, repaymentType, subscription,
                    new Financials(revenue, expenses, turnover, debtPayment), external);
        }
    }
}
