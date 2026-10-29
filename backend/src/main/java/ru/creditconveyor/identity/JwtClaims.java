package ru.creditconveyor.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

import ru.creditconveyor.api.RoleDto;

/**
 * Состав JWT: {@code sub}, {@code name}, {@code roles}. Компаний в токене нет — доступ проверяет {@link CompanyAccess}.
 */
final class JwtClaims {

    static final String NAME = "name";
    static final String ROLES = "roles";

    private JwtClaims() {
    }

    static CurrentUser toCurrentUser(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ROLES);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString(NAME),
                RoleDto.fromValue(roles.getFirst()));
    }
}
