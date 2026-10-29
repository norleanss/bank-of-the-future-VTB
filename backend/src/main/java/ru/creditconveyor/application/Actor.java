package ru.creditconveyor.application;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Автор перехода для истории заявки; у системы {@code id} — {@code null}.
 */
public record Actor(ActorType type, @Nullable UUID id) {

    public static Actor client(UUID userId) {
        return new Actor(ActorType.CLIENT, userId);
    }

    public static Actor employee(UUID userId) {
        return new Actor(ActorType.EMPLOYEE, userId);
    }

    public static Actor system() {
        return new Actor(ActorType.SYSTEM, null);
    }
}
