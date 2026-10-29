package ru.creditconveyor.notification;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.creditconveyor.api.NotificationPageDto;
import ru.creditconveyor.api.NotificationsApi;
import ru.creditconveyor.identity.CurrentUserProvider;

/**
 * Поток {@code /notifications/stream} — вне сгенерированного интерфейса ({@code x-internal} в контракте): генератор
 * не умеет {@code SseEmitter} для Spring MVC. Токен потока — параметр {@code access_token}, его проверяет
 * Spring Security.
 */
@RestController
class NotificationController implements NotificationsApi {

    private final NotificationService notifications;
    private final NotificationStream stream;
    private final CurrentUserProvider currentUserProvider;

    NotificationController(NotificationService notifications, NotificationStream stream,
                           CurrentUserProvider currentUserProvider) {
        this.notifications = notifications;
        this.stream = stream;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public ResponseEntity<NotificationPageDto> listNotifications(Boolean unreadOnly, Integer page, Integer size) {
        return ResponseEntity.ok(notifications.list(Boolean.TRUE.equals(unreadOnly), page, size));
    }

    @Override
    public ResponseEntity<Void> markNotificationRead(UUID id) {
        notifications.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamNotifications() {
        return stream.subscribe(currentUserProvider.currentUser().id());
    }
}
