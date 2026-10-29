package ru.creditconveyor;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Модуль обращается к другому только через классы его корневого пакета, без циклов. Без Spring-контекста и базы.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(CreditConveyorApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        // Диаграммы модулей (PlantUML) появятся в target/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
