package ru.creditconveyor.notification;

import java.time.Clock;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ru.creditconveyor.api.NotificationDto;
import ru.creditconveyor.application.ApplicationStatusChanged;

/**
 * Уведомление клиенту — автору заявки — о смене статуса. Повторная доставка того же события (после перезапуска)
 * второго уведомления не создаёт. В поток SSE уведомление уходит после фиксации: показываем только сохранённое.
 * Время уведомления — время перехода: события одной транзакции обрабатываются параллельно.
 */
@Component
class ClientNotifications {

    private final NotificationRepository notifications;
    private final NotificationStream stream;
    private final Clock clock;

    ClientNotifications(NotificationRepository notifications, NotificationStream stream, Clock clock) {
        this.notifications = notifications;
        this.stream = stream;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(ApplicationStatusChanged event) {
        NotificationTexts.of(event, clock.getZone())
                .filter(text -> !notifications.existsByEventId(event.eventId()))
                .ifPresent(text -> {
                    Notification notification = notifications.save(new Notification(event.eventId(),
                            event.applicantId(), event.applicationId(), text, event.occurredAt()));
                    NotificationDto dto = NotificationMapper.toDto(notification, clock.getZone());
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            stream.push(event.applicantId(), dto);
                        }
                    });
                });
    }
}
