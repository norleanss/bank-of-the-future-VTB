package ru.creditconveyor.scoring;

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
 * Решение по заявке: исход, причины, лимит и сумма предложения, кто решил. Только добавляется. У решения
 * сотрудника нет проверки источников и лимита.
 */
@Entity
@Table(schema = "scoring", name = "decisions")
class DecisionRecord {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "enrichment_id", nullable = false, updatable = false)
    private UUID enrichmentId;

    @Column(name = "scoring_result_id", updatable = false)
    private UUID scoringResultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Decision.Outcome outcome;

    @Column(name = "reason_codes", nullable = false, updatable = false)
    private String[] reasonCodes;

    @Column(name = "missing_fields", nullable = false, updatable = false)
    private String[] missingFields;

    @Column(name = "limit_amount", updatable = false)
    private BigDecimal limitAmount;

    @Column(name = "offered_amount", updatable = false)
    private BigDecimal offeredAmount;

    @Column(updatable = false)
    private Boolean preliminary;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private String decidedBy;

    @Column(name = "employee_id", updatable = false)
    private UUID employeeId;

    @Column(updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DecisionRecord() {
    }

    DecisionRecord(UUID applicationId, UUID enrichmentId, UUID scoringResultId, Decision decision, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.enrichmentId = enrichmentId;
        this.scoringResultId = scoringResultId;
        this.outcome = decision.outcome();
        this.reasonCodes = decision.reasons().stream().map(Reason::code).toArray(String[]::new);
        this.missingFields = decision.missingFields().toArray(String[]::new);
        this.limitAmount = decision.limit();
        this.offeredAmount = decision.offer() != null ? decision.offer().amount() : null;
        this.preliminary = decision.offer() != null ? decision.offer().preliminary() : null;
        this.decidedBy = "SYSTEM";
        this.createdAt = now;
    }

    DecisionRecord(UUID applicationId, EmployeeDecision decision, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.outcome = Decision.Outcome.valueOf(decision.outcome().name());
        this.reasonCodes = decision.reasonCodes().toArray(String[]::new);
        this.missingFields = decision.requestedFields().toArray(String[]::new);
        this.offeredAmount = decision.offeredAmount();
        this.preliminary = decision.offeredAmount() != null ? false : null;
        this.decidedBy = "EMPLOYEE";
        this.employeeId = decision.employeeId();
        this.comment = decision.comment();
        this.createdAt = now;
    }
}
