package ru.creditconveyor.enrichment;

import java.util.UUID;

/**
 * Данные внешних источников по заявке собраны и сохранены ({@code enrichmentId}) — можно принимать решение.
 *
 * @param round круг обработки заявки, для которого собраны данные
 */
public record CompanyDataCollected(UUID applicationId, UUID enrichmentId, int round, CompanyData data) {
}
