package ru.creditconveyor.application;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Запись истории только добавляется.
 */
@Entity
@Table(schema = "application", name = "status_history")
class StatusHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private ApplicationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StatusHistoryEntry() {
    }

    StatusHistoryEntry(ApplicationStatus fromStatus, ApplicationStatus toStatus, Actor actor, String comment,
                       Instant createdAt) {
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.comment = comment;
        this.createdAt = createdAt;
    }

    ApplicationStatus getFromStatus() {
        return fromStatus;
    }

    ApplicationStatus getToStatus() {
        return toStatus;
    }

    ActorType getActorType() {
        return actorType;
    }

    UUID getActorId() {
        return actorId;
    }

    String getComment() {
        return comment;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
