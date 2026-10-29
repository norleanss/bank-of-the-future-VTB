package ru.creditconveyor.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Уведомление пользователю в кабинете; {@code eventId} — событие, из которого оно создано (одно на событие).
 */
@Entity
@Table(schema = "notification", name = "notifications")
class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(name = "application_id", updatable = false)
    private UUID applicationId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    Notification(UUID eventId, UUID recipientId, UUID applicationId, NotificationTexts.Text text, Instant now) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.recipientId = recipientId;
        this.applicationId = applicationId;
        this.type = text.type();
        this.title = text.title();
        this.body = text.body();
        this.createdAt = now;
    }

    /** Повторная отметка не меняет время прочтения. */
    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    UUID getId() {
        return id;
    }

    UUID getRecipientId() {
        return recipientId;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getType() {
        return type;
    }

    String getTitle() {
        return title;
    }

    String getBody() {
        return body;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    boolean isRead() {
        return readAt != null;
    }
}
