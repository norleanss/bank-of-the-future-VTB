package ru.creditconveyor.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.product.ProductTerms;
import ru.creditconveyor.scoring.CreditCase.ExternalData;
import ru.creditconveyor.scoring.CreditCase.Financials;
import ru.creditconveyor.scoring.Decision.Outcome;
import ru.creditconveyor.scoring.ScoringProperties.LimitMethod;
import ru.creditconveyor.scoring.ScoringProperties.Limits;

/**
 * Кредитная политика: порядок проверок, лимит и итоговое решение. Скоринг ({@link Scorer}) только оценивает риск.
 * Без Spring и базы — всё нужное приходит в {@link CreditCase}.
 */
final class CreditPolicy {

    private final ScoringProperties properties;
    private final Scorer scorer;

    CreditPolicy(ScoringProperties properties) {
        this.properties = properties;
        this.scorer = new Scorer(properties);
        properties.requiredFields().values().stream().flatMap(List::stream).forEach(field -> {
            if (!Financials.FIELDS.contains(field)) {
                throw new IllegalArgumentException("app.scoring.required-fields: неизвестное поле " + field);
            }
        });
        // Лимит по выручке или оборотам считается только по обязательным полям, иначе решения не будет
        properties.limits().method().forEach((product, method) -> {
            String needed = switch (method) {
                case REVENUE -> "financials.annualRevenue";
                case TURNOVER -> "financials.bankMonthlyTurnover";
                case PRODUCT_MAX -> null;
            };
            if (needed != null && !properties.requiredFields().getOrDefault(product, List.of()).contains(needed)) {
                throw new IllegalArgumentException("app.scoring: лимит " + product + " считается по " + needed
                        + ", добавьте поле в required-fields");
            }
        });
    }

    Decision decide(CreditCase credit, LocalDate today) {
        ExternalData external = credit.external();
        if (!external.companyFound()) {
            return Decision.of(Outcome.REJECT, List.of(new Reason(Reason.COMPANY_NOT_FOUND,
                    "Компания не найдена в ЕГРЮЛ/ЕГРИП",
                    "Проверьте, что сведения о компании в ЕГРЮЛ/ЕГРИП актуальны, и подайте заявку снова")), null);
        }
        if (!external.unavailableSources().isEmpty()) {
            return Decision.of(Outcome.MANUAL_REVIEW, List.of(new Reason(Reason.SOURCE_UNAVAILABLE,
                    "Внешний источник данных временно недоступен — заявку проверит специалист")), null);
        }
        List<Reason> stopFactors = stopFactors(external);
        if (!stopFactors.isEmpty()) {
            return Decision.of(Outcome.REJECT, stopFactors, null);
        }
        Reason notEligible = eligibility(credit, today);
        if (notEligible != null) {
            return Decision.of(Outcome.REJECT, List.of(notEligible), null);
        }
        List<String> missing = missingFields(credit);
        if (!missing.isEmpty()) {
            return new Decision(Outcome.NEED_INFO, List.of(new Reason(Reason.MISSING_FIELDS,
                    "Укажите в анкете: " + Texts.fieldLabels(missing))), missing, null, null, null);
        }

        ScoreResult scoring = scorer.score(credit.financials(), external, today);
        if (scoring.score() < properties.rejectBelow()) {
            List<Reason> reasons = new ArrayList<>();
            reasons.add(new Reason(Reason.SCORE_LOW, "Оценка платёжеспособности ниже требуемой",
                    alternativeAdvice(credit, "Попробуйте меньшую сумму")));
            reasons.addAll(weakFactors(scoring));
            return Decision.of(Outcome.REJECT, reasons, scoring);
        }
        List<Reason> review = softFactors(credit.financials(), external);
        if (scoring.score() < properties.reviewBelow()) {
            review.addFirst(new Reason(Reason.SCORE_GREY_ZONE, "Требуется дополнительная проверка специалистом"));
        }
        if (!review.isEmpty()) {
            return Decision.of(Outcome.MANUAL_REVIEW, review, scoring);
        }

        ProductTerms product = credit.product();
        BigDecimal limit = limit(credit, scoring.grade());
        if (limit.compareTo(product.minAmount()) < 0) {
            return new Decision(Outcome.REJECT, List.of(new Reason(Reason.INSUFFICIENT_FINANCES,
                    "Финансовых показателей недостаточно для минимальной суммы продукта — "
                            + Texts.rubles(product.minAmount()),
                    smallerProductsAdvice(credit))),
                    List.of(), scoring, limit, null);
        }
        boolean aboveLimit = credit.requestedAmount().compareTo(limit) > 0;
        BigDecimal amount = aboveLimit ? limit : credit.requestedAmount();
        boolean preliminary = amount.compareTo(properties.preliminaryAbove()) > 0;
        ProposedOffer offer = OfferCalculator.offer(credit, amount, preliminary);
        List<Reason> reasons = aboveLimit
                ? List.of(new Reason(Reason.AMOUNT_ABOVE_LIMIT,
                        "Запрошенная сумма больше доступного лимита. Можем предложить " + Texts.rubles(limit),
                        "Примите предложение на доступную сумму или подайте новую заявку, когда вырастет выручка"))
                : List.of();
        return new Decision(aboveLimit ? Outcome.COUNTER_OFFER : Outcome.APPROVE, reasons, List.of(), scoring, limit,
                offer);
    }

    private List<Reason> stopFactors(ExternalData external) {
        var stop = properties.stopFactors();
        List<Reason> reasons = new ArrayList<>();
        String status = Objects.requireNonNull(external.companyStatus());
        if (stop.companyStatuses().contains(status)) {
            reasons.add("LIQUIDATED".equals(status)
                    ? new Reason(Reason.COMPANY_LIQUIDATED, "Компания ликвидирована")
                    : new Reason(Reason.COMPANY_LIQUIDATING, "Компания находится в процессе ликвидации"));
        }
        if (Objects.requireNonNull(external.maxOverdueDays12m()) > stop.maxOverdueDays()) {
            reasons.add(new Reason(Reason.OVERDUE_GT_60,
                    "Есть просрочка по кредиту больше " + stop.maxOverdueDays() + " дней",
                    "Погасите просрочку и подайте заявку снова через 3 месяца"));
        }
        if (Objects.requireNonNull(external.taxDebtAmount()).compareTo(stop.maxTaxDebt()) > 0) {
            reasons.add(new Reason(Reason.TAX_DEBT_HIGH,
                    "Налоговая задолженность больше " + Texts.rubles(stop.maxTaxDebt()),
                    "Погасите налоговую задолженность — сумму можно проверить в личном кабинете ФНС — и подайте заявку снова"));
        }
        return reasons;
    }

    /** «Продукт «Кредит одной суммой» доступен бизнесу от 12 месяцев», совет — Гамма-Старт, если подходит */
    private @Nullable Reason eligibility(CreditCase credit, LocalDate today) {
        ProductTerms product = credit.product();
        int age = Scorer.businessAgeMonths(Objects.requireNonNull(credit.external().registrationDate()), today);
        if (age >= product.minBusinessAgeMonths()) {
            return null;
        }
        int minAge = product.minBusinessAgeMonths();
        StringBuilder message = new StringBuilder("Продукт «").append(product.name()).append("» доступен бизнесу от ")
                .append(minAge).append(' ').append(Texts.plural(minAge, "месяца", "месяцев", "месяцев"));
        ProductTerms alternative = credit.alternative();
        String advice = alternative != null && !alternative.code().equals(product.code())
                && age >= alternative.minBusinessAgeMonths()
                && credit.requestedAmount().compareTo(alternative.minAmount()) >= 0
                && credit.requestedAmount().compareTo(alternative.maxAmount()) <= 0
                ? "Подайте заявку на «" + alternative.name() + "» — он для нового бизнеса и без финансовых документов"
                : "Подайте заявку, когда бизнесу исполнится " + minAge + " "
                        + Texts.plural(minAge, "месяц", "месяца", "месяцев");
        return new Reason(Reason.PRODUCT_NOT_ELIGIBLE, message.toString(), advice);
    }

    /** Продукты каталога с меньшей минимальной суммой, кроме самого продукта заявки. */
    private static String smallerProductsAdvice(CreditCase credit) {
        ProductTerms product = credit.product();
        List<String> smaller = credit.catalog().stream()
                .filter(other -> !other.code().equals(product.code()))
                .filter(other -> other.minAmount().compareTo(product.minAmount()) < 0)
                .map(other -> "«" + other.name() + "»")
                .toList();
        return smaller.isEmpty()
                ? "Подайте заявку снова, когда вырастут выручка или обороты по счёту"
                : "Попробуйте продукт с меньшей минимальной суммой — " + String.join(" или ", smaller);
    }

    /** «Попробуйте меньшую сумму или Гамма-Старт», если заявка не на Гамма-Старт */
    private static String alternativeAdvice(CreditCase credit, String advice) {
        ProductTerms alternative = credit.alternative();
        return alternative != null && !alternative.code().equals(credit.product().code())
                ? advice + " или " + alternative.name()
                : advice;
    }

    private List<String> missingFields(CreditCase credit) {
        return properties.requiredFields().getOrDefault(credit.product().code(), List.of()).stream()
                .filter(field -> credit.financials().value(field) == null)
                .toList();
    }

    private List<Reason> weakFactors(ScoreResult scoring) {
        return scoring.factors().stream()
                .filter(f -> f.factor().weakReason() != null)
                .filter(f -> BigDecimal.valueOf(f.points())
                        .compareTo(properties.weakFactorShare().multiply(BigDecimal.valueOf(f.maxPoints()))) < 0)
                .map(f -> Objects.requireNonNull(f.factor().weakReason()))
                .toList();
    }

    private List<Reason> softFactors(Financials financials, ExternalData external) {
        var soft = properties.softFactors();
        List<Reason> reasons = new ArrayList<>();
        int overdue = Objects.requireNonNull(external.maxOverdueDays12m());
        if (overdue >= soft.overdueDaysFrom() && overdue <= properties.stopFactors().maxOverdueDays()) {
            reasons.add(new Reason(Reason.OVERDUE_31_60, "Была просрочка по кредиту от " + soft.overdueDaysFrom()
                    + " до " + properties.stopFactors().maxOverdueDays() + " дней"));
        }
        BigDecimal revenue = financials.annualRevenue();
        BigDecimal expenses = financials.annualExpenses();
        if (soft.loss() && revenue != null && expenses != null && expenses.compareTo(revenue) >= 0) {
            reasons.add(new Reason(Reason.LOSS_MAKING, "По данным анкеты расходы за год не меньше выручки"));
        }
        return reasons;
    }

    /** Лимит по финансам: округление вниз до шага, не больше потолка продукта и не меньше нуля. */
    BigDecimal limit(CreditCase credit, String grade) {
        Limits limits = properties.limits();
        ProductTerms product = credit.product();
        LimitMethod method = limits.method().get(product.code());
        if (method == null) {
            throw new IllegalStateException("app.scoring.limits.method: нет метода для продукта " + product.code());
        }
        Financials financials = credit.financials();
        BigDecimal raw = switch (method) {
            case REVENUE -> Objects.requireNonNull(financials.annualRevenue())
                    .multiply(limits.revenueShareByGrade().getOrDefault(grade, BigDecimal.ZERO))
                    .subtract(Objects.requireNonNull(credit.external().totalDebtAmount()));
            case TURNOVER -> Objects.requireNonNull(financials.bankMonthlyTurnover()).multiply(limits.turnoverShare());
            case PRODUCT_MAX -> product.maxAmount();
        };
        BigDecimal step = limits.roundingStep();
        BigDecimal rounded = raw.divide(step, 0, RoundingMode.FLOOR).multiply(step).max(BigDecimal.ZERO);
        return rounded.min(product.maxAmount()).setScale(2, RoundingMode.UNNECESSARY);
    }
}
