package ru.creditconveyor.application;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Документ заявки: описание без содержимого (содержимое — {@link DocumentContent}). Прикладывается к ответу
 * на запрос сведений один раз.
 */
@Entity
@Table(schema = "application", name = "documents")
class ApplicationDocument {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "info_request_id")
    private UUID infoRequestId;

    @Column(name = "document_type", nullable = false, updatable = false)
    private String documentType;

    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "mime_type", nullable = false, updatable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(nullable = false, updatable = false)
    private String sha256;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected ApplicationDocument() {
    }

    ApplicationDocument(UUID applicationId, String documentType, String fileName, String mimeType, long sizeBytes,
                        String sha256, UUID uploadedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.applicationId = applicationId;
        this.documentType = documentType;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = now;
    }

    void attachTo(UUID requestId) {
        if (infoRequestId != null) {
            throw new IllegalStateException("Документ " + id + " уже приложен к ответу");
        }
        infoRequestId = requestId;
    }

    boolean isAttached() {
        return infoRequestId != null;
    }

    UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    UUID getInfoRequestId() {
        return infoRequestId;
    }

    String getDocumentType() {
        return documentType;
    }

    String getFileName() {
        return fileName;
    }

    String getMimeType() {
        return mimeType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    Instant getUploadedAt() {
        return uploadedAt;
    }
}
