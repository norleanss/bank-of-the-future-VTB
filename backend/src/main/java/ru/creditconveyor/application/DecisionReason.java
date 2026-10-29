package ru.creditconveyor.application;

import org.jspecify.annotations.Nullable;

/**
 * Причина решения, которую видит клиент: код из справочника {@code reasonCodes}, текст и совет, что делать дальше
 * (у отказа и контрпредложения).
 */
public record DecisionReason(String code, String message, @Nullable String advice) {

    public DecisionReason(String code, String message) {
        this(code, message, null);
    }
}
