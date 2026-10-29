package ru.creditconveyor.enrichment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;

/**
 * Ответ одного источника: статус, число попыток и тело как есть.
 */
@Entity
@Table(schema = "enrichment", name = "source_results")
class SourceResult {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SourceStatus status;

    @Column(nullable = false, updatable = false)
    private int attempts;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String raw;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    protected SourceResult() {
    }

    /** Тело, которое не разобрали, сохраняется как ошибка источника. */
    SourceResult(SourceClient.Response response, SourceStatus status, String raw, String errorMessage) {
        this.id = UUID.randomUUID();
        this.source = response.source();
        this.status = status;
        this.attempts = response.attempts();
        this.httpStatus = response.httpStatus();
        this.raw = raw;
        this.errorMessage = errorMessage;
        this.requestedAt = response.requestedAt();
        this.receivedAt = response.receivedAt();
    }

    Source getSource() {
        return source;
    }

    SourceStatus getStatus() {
        return status;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }
}
