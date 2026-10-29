package ru.creditconveyor.notification;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import ru.creditconveyor.api.NotificationDto;

/**
 * Открытые соединения SSE по пользователям, в памяти процесса: монолит — один экземпляр. Пропущенное за время
 * обрыва клиент берёт из {@code GET /notifications}. Сразу после подключения и раз в интервал — {@code ping}:
 * клиент видит, что соединение живо, а прокси не закрывают его как простаивающее.
 */
@Component
class NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(NotificationStream.class);

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final NotificationProperties properties;
    /** Свой поток: медленный клиент не задерживает общий планировщик (истечение предложений и др.). */
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sse-heartbeat").daemon().factory());

    NotificationStream(NotificationProperties properties) {
        this.properties = properties;
        long interval = properties.heartbeatInterval().toMillis();
        heartbeats.scheduleWithFixedDelay(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
    }

    SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        emitters.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> remove(userId, emitter));
        send(userId, emitter, ping());
        return emitter;
    }

    void push(UUID userId, NotificationDto notification) {
        emitters.getOrDefault(userId, Set.of()).forEach(emitter -> send(userId, emitter,
                SseEmitter.event().name("notification").data(notification, MediaType.APPLICATION_JSON)));
    }

    /** Ошибка не останавливает расписание: следующее исключение в {@code scheduleWithFixedDelay} отменило бы его. */
    void heartbeat() {
        try {
            emitters.forEach((userId, userEmitters) -> userEmitters.forEach(emitter -> send(userId, emitter, ping())));
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить ping в поток уведомлений", e);
        }
    }

    int connections(UUID userId) {
        return emitters.getOrDefault(userId, Set.of()).size();
    }

    /** Клиент закрыл вкладку — соединение убираем, на остальные это не влияет. */
    private void send(UUID userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("Соединение SSE пользователя {} закрыто: {}", userId, e.getMessage());
            remove(userId, emitter);
            emitter.complete();
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        emitters.computeIfPresent(userId, (id, userEmitters) -> {
            userEmitters.remove(emitter);
            return userEmitters.isEmpty() ? null : userEmitters;
        });
    }

    private static SseEmitter.SseEventBuilder ping() {
        return SseEmitter.event().name("ping").data("");
    }
}
