package ru.creditconveyor.product;

import java.util.Set;
import java.util.stream.Collectors;

import ru.creditconveyor.api.DictionaryItemDto;

/**
 * Коды причин из справочника {@code reasonCodes} — для проверки кодов, которые передаёт сотрудник банка.
 */
public final class ReasonCodes {

    private static final Set<String> CODES = DictionariesController.DICTIONARIES.getReasonCodes().stream()
            .map(DictionaryItemDto::getCode)
            .collect(Collectors.toUnmodifiableSet());

    private ReasonCodes() {
    }

    public static boolean isKnown(String code) {
        return CODES.contains(code);
    }
}
