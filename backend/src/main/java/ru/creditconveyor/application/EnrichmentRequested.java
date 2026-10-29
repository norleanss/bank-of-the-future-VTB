package ru.creditconveyor.application;

import java.util.UUID;

/**
 * Заявку нужно (снова) проверить по внешним источникам: клиент отправил её или ответил на запрос банка.
 *
 * @param round круг обработки: событие прежнего круга, доставленное позже, игнорируется
 */
public record EnrichmentRequested(UUID applicationId, UUID companyId, int round) {
}
