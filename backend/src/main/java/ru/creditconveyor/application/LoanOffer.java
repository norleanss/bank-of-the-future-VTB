package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Статус предложения меняет только {@link LoanApplication}.
 */
@Entity
@Table(schema = "application", name = "offers")
class LoanOffer {

    @Id
    private UUID id;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private int term;

    @Column(name = "term_unit", nullable = false, updatable = false)
    private String termUnit;

    /** LOAN — срок кредита, TRANCHE — срок транша: от этого зависит, как описать условия клиенту. */
    @Column(name = "term_kind", nullable = false, updatable = false)
    private String termKind;

    @Column(name = "agreement_term_months", updatable = false)
    private Integer agreementTermMonths;

    @Column(nullable = false, updatable = false)
    private BigDecimal rate;

    @Column(name = "rate_unit", nullable = false, updatable = false)
    private String rateUnit;

    @Column(name = "monthly_payment", updatable = false)
    private BigDecimal monthlyPayment;

    @Column(nullable = false, updatable = false)
    private boolean subscription;

    @Column(name = "subscription_fee", updatable = false)
    private BigDecimal subscriptionFee;

    @Column(nullable = false, updatable = false)
    private boolean preliminary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OfferStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LoanOffer() {
    }

    LoanOffer(OfferTerms terms, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.productCode = terms.productCode().getValue();
        this.productName = terms.productName();
        this.amount = terms.amount();
        this.term = terms.term();
        this.termUnit = terms.termUnit().getValue();
        this.termKind = terms.termKind().getValue();
        this.agreementTermMonths = terms.agreementTermMonths();
        this.rate = terms.rate();
        this.rateUnit = terms.rateUnit().getValue();
        this.monthlyPayment = terms.monthlyPayment();
        this.subscription = terms.subscription();
        this.subscriptionFee = terms.subscriptionFee();
        this.preliminary = terms.preliminary();
        this.status = OfferStatus.ACTIVE;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Граница срока включительно. */
    boolean isDue(Instant now) {
        return status == OfferStatus.ACTIVE && !now.isBefore(expiresAt);
    }

    void accept() {
        status = OfferStatus.ACCEPTED;
    }

    void decline() {
        status = OfferStatus.DECLINED;
    }

    void expire() {
        status = OfferStatus.EXPIRED;
    }

    void supersede() {
        status = OfferStatus.SUPERSEDED;
    }

    String getProductCode() {
        return productCode;
    }

    String getProductName() {
        return productName;
    }

    BigDecimal getAmount() {
        return amount;
    }

    int getTerm() {
        return term;
    }

    String getTermKind() {
        return termKind;
    }

    String getTermUnit() {
        return termUnit;
    }

    Integer getAgreementTermMonths() {
        return agreementTermMonths;
    }

    BigDecimal getRate() {
        return rate;
    }

    String getRateUnit() {
        return rateUnit;
    }

    BigDecimal getMonthlyPayment() {
        return monthlyPayment;
    }

    boolean isSubscription() {
        return subscription;
    }

    BigDecimal getSubscriptionFee() {
        return subscriptionFee;
    }

    boolean isPreliminary() {
        return preliminary;
    }

    OfferStatus getStatus() {
        return status;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}
