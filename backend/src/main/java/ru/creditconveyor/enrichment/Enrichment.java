package ru.creditconveyor.enrichment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * Одна проверка заявки по источникам: нормализованный профиль и ответы источников как есть. Только добавляется —
 * повторная проверка после ответа клиента создаёт новую запись.
 */
@Entity
@Table(schema = "enrichment", name = "enrichments")
class Enrichment {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(updatable = false)
    private String inn;

    @Column(name = "company_found", nullable = false, updatable = false)
    private boolean companyFound;

    @Column(name = "company_name", updatable = false)
    private String companyName;

    @Column(updatable = false)
    private String ogrn;

    @Column(name = "business_type", updatable = false)
    private String businessType;

    @Column(name = "company_status", updatable = false)
    private String companyStatus;

    @Column(name = "registration_date", updatable = false)
    private LocalDate registrationDate;

    @Column(updatable = false)
    private String okved;

    @Column(updatable = false)
    private String region;

    @Column(name = "tax_debt_amount", updatable = false)
    private BigDecimal taxDebtAmount;

    @Column(name = "active_credits_count", updatable = false)
    private Integer activeCreditsCount;

    @Column(name = "total_debt_amount", updatable = false)
    private BigDecimal totalDebtAmount;

    @Column(name = "max_overdue_days_12m", updatable = false)
    private Integer maxOverdueDays12m;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "enrichment_id", nullable = false, updatable = false)
    private List<SourceResult> sources = new ArrayList<>();

    protected Enrichment() {
    }

    Enrichment(UUID applicationId, String inn, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.inn = inn;
        this.companyFound = true;
        this.createdAt = now;
    }

    void companyNotFound() {
        this.companyFound = false;
    }

    void registry(String name, String ogrn, String businessType, String status, LocalDate registrationDate,
                  String okved, String region) {
        this.companyName = name;
        this.ogrn = ogrn;
        this.businessType = businessType;
        this.companyStatus = status;
        this.registrationDate = registrationDate;
        this.okved = okved;
        this.region = region;
    }

    void taxes(BigDecimal taxDebtAmount) {
        this.taxDebtAmount = taxDebtAmount;
    }

    void creditHistory(int activeCreditsCount, BigDecimal totalDebtAmount, int maxOverdueDays12m) {
        this.activeCreditsCount = activeCreditsCount;
        this.totalDebtAmount = totalDebtAmount;
        this.maxOverdueDays12m = maxOverdueDays12m;
    }

    void add(SourceResult result) {
        sources.add(result);
    }

    CompanyData toCompanyData() {
        List<String> unavailable = sources.stream()
                .filter(result -> result.getStatus() != SourceStatus.OK)
                .filter(result -> !(result.getSource() == Source.EGRUL && result.getStatus() == SourceStatus.NOT_FOUND))
                .map(result -> result.getSource().name())
                .toList();
        return new CompanyData(companyFound, unavailable, companyStatus, registrationDate, taxDebtAmount,
                activeCreditsCount, totalDebtAmount, maxOverdueDays12m);
    }

    UUID getId() {
        return id;
    }

    BigDecimal getTaxDebtAmount() {
        return taxDebtAmount;
    }

    Integer getActiveCreditsCount() {
        return activeCreditsCount;
    }

    BigDecimal getTotalDebtAmount() {
        return totalDebtAmount;
    }

    Integer getMaxOverdueDays12m() {
        return maxOverdueDays12m;
    }

    List<SourceResult> getSources() {
        return sources;
    }
}
