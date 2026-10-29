package ru.creditconveyor.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Сколько непрочитанных уведомлений у пользователя по каждой заявке — для {@code unreadNotifications} в списке.
 * Реализует модуль уведомлений: он зависит от заявок, а не наоборот.
 */
public interface UnreadNotifications {

    /** Заявок без непрочитанных в ответе нет. */
    Map<UUID, Integer> countByApplication(UUID recipientId, Collection<UUID> applicationIds);
}
