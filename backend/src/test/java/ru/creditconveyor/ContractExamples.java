package ru.creditconveyor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Примеры из контракта (при сборке копируется в {@code static/openapi.yaml}): бэкенд сверяется с тем,
 * что фронтенд видит в Prism.
 */
public final class ContractExamples {

    private static final Map<String, Object> SPEC = load();

    private ContractExamples() {
    }

    public static Object requestExample(String path, String method) {
        return media(operation(path, method).get("requestBody")).get("example");
    }

    public static Object responseExample(String path, String method, String code) {
        Map<String, Object> responses = map(operation(path, method).get("responses"));
        return media(responses.get(code)).get("example");
    }

    private static Map<String, Object> operation(String path, String method) {
        return map(map(map(SPEC.get("paths")).get(path)).get(method));
    }

    private static Map<String, Object> media(Object holder) {
        return map(map(map(holder).get("content")).get("application/json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> load() {
        try (InputStream spec = ContractExamples.class.getResourceAsStream("/static/openapi.yaml")) {
            return new Yaml().load(spec);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать контракт", e);
        }
    }
}
