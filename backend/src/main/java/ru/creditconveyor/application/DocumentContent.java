package ru.creditconveyor.application;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Содержимое документа — отдельно от описания: список документов файлы не читает.
 */
@Entity
@Table(schema = "application", name = "document_contents")
class DocumentContent {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private byte[] content;

    protected DocumentContent() {
    }

    DocumentContent(UUID documentId, byte[] content) {
        this.documentId = documentId;
        this.content = content;
    }

    byte[] getContent() {
        return content;
    }
}
