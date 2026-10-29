package ru.creditconveyor.application;

import java.util.UUID;

/**
 * Клиент подписал договор кодом — дальше моделируется выдача.
 */
public record ContractSigned(UUID applicationId) {
}
