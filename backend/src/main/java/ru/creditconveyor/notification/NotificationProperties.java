package ru.creditconveyor.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Поток уведомлений ({@code app.notifications}): интервал ping и срок жизни соединения SSE.
 */
@ConfigurationProperties("app.notifications")
record NotificationProperties(Duration heartbeatInterval, Duration streamTimeout) {
}
