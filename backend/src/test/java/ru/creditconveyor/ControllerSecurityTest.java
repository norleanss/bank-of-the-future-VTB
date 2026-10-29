package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * {@code @PreAuthorize} на классе не действует на унаследованные default-методы — без явного переопределения
 * новый эндпоинт из контракта окажется доступен любой роли.
 */
class ControllerSecurityTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "ru.creditconveyor.backoffice.BackofficeController, ru.creditconveyor.api.BackofficeApi, hasRole('UNDERWRITER')",
            "ru.creditconveyor.application.ApplicationController, ru.creditconveyor.api.ApplicationsApi, hasRole('CLIENT')"
    })
    void roleRestrictedControllerOverridesEveryApiMethod(String controllerName, String apiName, String rule)
            throws Exception {
        Class<?> controller = Class.forName(controllerName);
        Class<?> api = Class.forName(apiName);

        assertThat(controller.getAnnotation(PreAuthorize.class)).isNotNull()
                .extracting(PreAuthorize::value).isEqualTo(rule);
        Arrays.stream(api.getDeclaredMethods())
                .filter(Method::isDefault)
                .filter(method -> !method.getName().equals("getRequest"))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .forEach(method -> assertThat(declares(controller, method))
                        .as("%s должен переопределить %s", controller.getSimpleName(), method.getName())
                        .isTrue());
    }

    private static boolean declares(Class<?> type, Method method) {
        try {
            type.getDeclaredMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
