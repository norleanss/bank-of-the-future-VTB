package ru.creditconveyor.scoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * Сохранённый балл с разбивкой — для карточки андеррайтера и разбора решения.
 */
@Entity
@Table(schema = "scoring", name = "scoring_results")
class ScoringRecord {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(nullable = false, updatable = false)
    private int score;

    @Column(nullable = false, updatable = false)
    private String grade;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ElementCollection
    @CollectionTable(schema = "scoring", name = "scoring_factors", joinColumns = @JoinColumn(name = "scoring_result_id"))
    @OrderColumn(name = "position")
    private List<FactorRow> factors = new ArrayList<>();

    protected ScoringRecord() {
    }

    ScoringRecord(UUID applicationId, ScoreResult result, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.score = result.score();
        this.grade = result.grade();
        this.createdAt = now;
        result.factors().forEach(factor -> factors.add(new FactorRow(factor)));
    }

    UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    int getScore() {
        return score;
    }

    String getGrade() {
        return grade;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<FactorRow> getFactors() {
        return factors;
    }

    @Embeddable
    static class FactorRow {

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private Factor factor;

        @Column(name = "input_value")
        private String inputValue;

        @Column(nullable = false)
        private int points;

        @Column(name = "max_points", nullable = false)
        private int maxPoints;

        @Column(nullable = false)
        private String explanation;

        protected FactorRow() {
        }

        FactorRow(ScoreResult.FactorResult result) {
            this.factor = result.factor();
            this.inputValue = result.inputValue();
            this.points = result.points();
            this.maxPoints = result.maxPoints();
            this.explanation = result.explanation();
        }

        Factor getFactor() {
            return factor;
        }

        String getInputValue() {
            return inputValue;
        }

        int getPoints() {
            return points;
        }

        int getMaxPoints() {
            return maxPoints;
        }

        String getExplanation() {
            return explanation;
        }
    }
}
