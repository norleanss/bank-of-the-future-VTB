package ru.creditconveyor.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndReadAtIsNull(UUID recipientId, Pageable pageable);

    boolean existsByEventId(UUID eventId);

    @Query("""
            SELECT n.applicationId, count(n) FROM Notification n
            WHERE n.recipientId = :recipientId AND n.readAt IS NULL AND n.applicationId IN :applicationIds
            GROUP BY n.applicationId""")
    List<Object[]> countUnreadByApplication(UUID recipientId, Collection<UUID> applicationIds);
}
