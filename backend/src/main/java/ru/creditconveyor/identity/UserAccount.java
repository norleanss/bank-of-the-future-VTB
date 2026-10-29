package ru.creditconveyor.identity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ru.creditconveyor.api.RoleDto;

@Entity
@Table(schema = "identity", name = "users")
class UserAccount {

    @Id
    private UUID id;

    /** Ключ демо-пользователя для входа без пароля */
    @Column(nullable = false)
    private String login;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String role;

    protected UserAccount() {
    }

    UUID getId() {
        return id;
    }

    String getFullName() {
        return fullName;
    }

    CurrentUser toCurrentUser() {
        return new CurrentUser(id, fullName, RoleDto.fromValue(role));
    }
}
