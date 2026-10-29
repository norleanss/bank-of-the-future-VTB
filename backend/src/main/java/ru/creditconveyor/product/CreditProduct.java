package ru.creditconveyor.product;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ru.creditconveyor.api.FundingModeDto;
import ru.creditconveyor.api.RateUnitDto;
import ru.creditconveyor.api.RepaymentTypeDto;
import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Продукт каталога. Каталог меняется только миграциями.
 */
@Entity
@Table(schema = "product", name = "credit_products")
class CreditProduct {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "min_amount", nullable = false)
    private BigDecimal minAmount;

    @Column(name = "max_amount", nullable = false)
    private BigDecimal maxAmount;

    @Column(name = "term_kind", nullable = false)
    private String termKind;

    @Column(name = "term_unit", nullable = false)
    private String termUnit;

    @Column(name = "min_term", nullable = false)
    private int minTerm;

    @Column(name = "max_term", nullable = false)
    private int maxTerm;

    @Column(name = "max_term_with_subscription")
    private Integer maxTermWithSubscription;

    @Column(name = "agreement_term_months")
    private Integer agreementTermMonths;

    @Column(name = "auto_renewal", nullable = false)
    private boolean autoRenewal;

    @Column(name = "rate_unit", nullable = false)
    private String rateUnit;

    @Column(name = "base_rate", nullable = false)
    private BigDecimal baseRate;

    @Column(name = "rate_with_subscription")
    private BigDecimal rateWithSubscription;

    @Column(name = "subscription_description")
    private String subscriptionDescription;

    @Column(name = "subscription_fee_percent")
    private BigDecimal subscriptionFeePercent;

    @Column(name = "funding_modes", nullable = false)
    private String[] fundingModes;

    @Column(name = "repayment_types", nullable = false)
    private String[] repaymentTypes;

    @Column(name = "min_business_age_months", nullable = false)
    private int minBusinessAgeMonths;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    protected CreditProduct() {
    }

    /** Для тестов правил без базы. */
    CreditProduct(String code, BigDecimal minAmount, BigDecimal maxAmount, TermKindDto termKind, TermUnitDto termUnit,
                  int minTerm, int maxTerm, Integer maxTermWithSubscription, List<FundingModeDto> fundingModes,
                  int minBusinessAgeMonths, int sortOrder, RateUnitDto rateUnit, BigDecimal baseRate,
                  BigDecimal rateWithSubscription, BigDecimal subscriptionFeePercent) {
        this.code = code;
        this.name = code;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.termKind = termKind.getValue();
        this.termUnit = termUnit.getValue();
        this.minTerm = minTerm;
        this.maxTerm = maxTerm;
        this.maxTermWithSubscription = maxTermWithSubscription;
        this.rateUnit = rateUnit.getValue();
        this.baseRate = baseRate;
        this.rateWithSubscription = rateWithSubscription;
        this.subscriptionFeePercent = subscriptionFeePercent;
        this.fundingModes = fundingModes.stream().map(FundingModeDto::getValue).toArray(String[]::new);
        this.repaymentTypes = new String[] {RepaymentTypeDto.ANNUITY.getValue()};
        this.minBusinessAgeMonths = minBusinessAgeMonths;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    String getCode() {
        return code;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getMinAmount() {
        return minAmount;
    }

    BigDecimal getMaxAmount() {
        return maxAmount;
    }

    TermKindDto getTermKind() {
        return TermKindDto.fromValue(termKind);
    }

    TermUnitDto getTermUnit() {
        return TermUnitDto.fromValue(termUnit);
    }

    int getMinTerm() {
        return minTerm;
    }

    int getMaxTerm() {
        return maxTerm;
    }

    Integer getMaxTermWithSubscription() {
        return maxTermWithSubscription;
    }

    Integer getAgreementTermMonths() {
        return agreementTermMonths;
    }

    boolean isAutoRenewal() {
        return autoRenewal;
    }

    RateUnitDto getRateUnit() {
        return RateUnitDto.fromValue(rateUnit);
    }

    BigDecimal getBaseRate() {
        return baseRate;
    }

    BigDecimal getRateWithSubscription() {
        return rateWithSubscription;
    }

    String getSubscriptionDescription() {
        return subscriptionDescription;
    }

    BigDecimal getSubscriptionFeePercent() {
        return subscriptionFeePercent;
    }

    List<FundingModeDto> getFundingModes() {
        return Arrays.stream(fundingModes).map(FundingModeDto::fromValue).toList();
    }

    List<RepaymentTypeDto> getRepaymentTypes() {
        return Arrays.stream(repaymentTypes).map(RepaymentTypeDto::fromValue).toList();
    }

    int getMinBusinessAgeMonths() {
        return minBusinessAgeMonths;
    }

    int getSortOrder() {
        return sortOrder;
    }
}
