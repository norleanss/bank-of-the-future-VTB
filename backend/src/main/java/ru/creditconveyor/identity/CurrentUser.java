package ru.creditconveyor.identity;

import java.util.UUID;

import ru.creditconveyor.api.RoleDto;

public record CurrentUser(UUID id, String fullName, RoleDto role) {
}
