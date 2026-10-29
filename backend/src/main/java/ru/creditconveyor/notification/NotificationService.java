package ru.creditconveyor.notification;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ru.creditconveyor.api.NotificationPageDto;
import ru.creditconveyor.application.UnreadNotifications;
import ru.creditconveyor.identity.CurrentUserProvider;

/**
 * Пользователь видит только свои уведомления; чужое и несуществующее — одинаковый 404.
 */
@Service
@Transactional(readOnly = true)
class NotificationService implements UnreadNotifications {

    private final NotificationRepository notifications;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, CurrentUserProvider currentUserProvider, Clock clock) {
        this.notifications = notifications;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    /** Новые первыми. */
    NotificationPageDto list(boolean unreadOnly, int page, int size) {
        UUID userId = currentUserProvider.currentUser().id();
        PageRequest request = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<Notification> result = unreadOnly
                ? notifications.findByRecipientIdAndReadAtIsNull(userId, request)
                : notifications.findByRecipientId(userId, request);
        return new NotificationPageDto(
                result.getNumber(),
                result.getSize(),
                Math.toIntExact(result.getTotalElements()),
                result.getTotalPages(),
                result.getContent().stream().map(n -> NotificationMapper.toDto(n, clock.getZone())).toList());
    }

    @Transactional
    void markRead(UUID id) {
        UUID userId = currentUserProvider.currentUser().id();
        notifications.findById(id)
                .filter(notification -> notification.getRecipientId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Уведомление не найдено"))
                .markRead(clock.instant());
    }

    @Override
    public Map<UUID, Integer> countByApplication(UUID recipientId, Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        return notifications.countUnreadByApplication(recipientId, applicationIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Long) row[1]).intValue()));
    }
}
