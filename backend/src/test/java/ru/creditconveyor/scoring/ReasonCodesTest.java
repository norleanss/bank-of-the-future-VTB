package ru.creditconveyor.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ru.creditconveyor.ContractExamples;
import ru.creditconveyor.application.ApplicationStateMachine;

/**
 * Справочник {@code reasonCodes} контракта — все коды причин политики, сбой обработки, изменение условий продукта
 * и решения андеррайтера.
 */
class ReasonCodesTest {

    @Test
    void everyPolicyReasonIsInContractDictionary() {
        List<String> policyCodes = Stream.concat(
                        Arrays.stream(Reason.class.getDeclaredFields())
                                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                                .map(ReasonCodesTest::value),
                        Arrays.stream(Factor.values()).map(Factor::weakReason).filter(Objects::nonNull).map(Reason::code))
                .toList();

        @SuppressWarnings("unchecked")
        var dictionary = (Map<String, List<Map<String, String>>>)
                ContractExamples.responseExample("/dictionaries", "get", "200");
        List<String> contractCodes = dictionary.get("reasonCodes").stream().map(item -> item.get("code")).toList();

        assertThat(policyCodes).hasSize(20);
        assertThat(contractCodes).containsExactlyInAnyOrderElementsOf(Stream.concat(policyCodes.stream(),
                Stream.of(ApplicationStateMachine.PROCESSING_ERROR, ApplicationStateMachine.PRODUCT_CONDITIONS_CHANGED,
                        "MANUAL_REJECT", "MANUAL_COUNTER_OFFER"))
                .toList());
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
