package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import ru.creditconveyor.api.TermKindDto;
import ru.creditconveyor.api.TermUnitDto;

/**
 * Статус заявки изменился. Публикуется при каждом переходе, в транзакции перехода; данные заявки — на момент
 * сохранения (причины, оффер и запрос сведений уже нового решения).
 *
 * @param eventId            для обработчиков, которым важна однократность (повторная доставка после перезапуска)
 * @param fromStatus         нет у первой записи — создания черновика (о нём событие не публикуется)
 * @param offer              текущее предложение, если оно есть
 * @param infoRequestMessage текст открытого запроса сведений
 */
public record ApplicationStatusChanged(
        UUID eventId,
        UUID applicationId,
        String number,
        UUID companyId,
        UUID applicantId,
        @Nullable ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        ActorType actorType,
        Instant occurredAt,
        List<DecisionReason> reasons,
        @Nullable Offer offer,
        @Nullable String infoRequestMessage) {

    public record Offer(String productName, BigDecimal amount, int term, TermUnitDto termUnit, TermKindDto termKind,
                        boolean preliminary, Instant expiresAt) {
    }
}
