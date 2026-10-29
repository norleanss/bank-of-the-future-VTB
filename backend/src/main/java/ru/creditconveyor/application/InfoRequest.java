package ru.creditconveyor.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Запрос банка клиенту: какие поля анкеты дозаполнить и какие документы приложить. Статус меняет только
 * {@link LoanApplication}.
 */
@Entity
@Table(schema = "application", name = "info_requests")
class InfoRequest {

    static final String OPEN = "OPEN";
    static final String ANSWERED = "ANSWERED";

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(name = "requested_fields", nullable = false, updatable = false)
    private String[] requestedFields;

    @Column(name = "requested_documents", nullable = false, updatable = false)
    private String[] requestedDocuments;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(nullable = false)
    private String status;

    private String response;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    protected InfoRequest() {
    }

    InfoRequest(String message, List<String> requestedFields, List<String> requestedDocuments, ActorType createdBy,
                Instant now) {
        this.id = UUID.randomUUID();
        this.message = message;
        this.requestedFields = requestedFields.toArray(String[]::new);
        this.requestedDocuments = requestedDocuments.toArray(String[]::new);
        this.createdBy = createdBy.name();
        this.status = OPEN;
        this.createdAt = now;
    }

    void answer(String response, Instant now) {
        this.status = ANSWERED;
        this.response = response;
        this.answeredAt = now;
    }

    boolean isOpen() {
        return OPEN.equals(status);
    }

    UUID getId() {
        return id;
    }

    String getMessage() {
        return message;
    }

    List<String> getRequestedFields() {
        return List.of(requestedFields);
    }

    List<String> getRequestedDocuments() {
        return List.of(requestedDocuments);
    }

    String getCreatedBy() {
        return createdBy;
    }

    String getStatus() {
        return status;
    }

    String getResponse() {
        return response;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
