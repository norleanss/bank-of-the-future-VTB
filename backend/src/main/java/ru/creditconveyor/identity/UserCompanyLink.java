package ru.creditconveyor.identity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Компания в списке пользователя; {@code demoScenario} — подпись в переключателе компании (только демо).
 */
@Entity
@Table(schema = "identity", name = "user_companies")
@IdClass(UserCompanyLink.Key.class)
class UserCompanyLink {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "demo_scenario")
    private String demoScenario;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected UserCompanyLink() {
    }

    UUID getCompanyId() {
        return companyId;
    }

    String getDemoScenario() {
        return demoScenario;
    }

    static class Key implements Serializable {

        private UUID userId;
        private UUID companyId;

        protected Key() {
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(userId, key.userId) && Objects.equals(companyId, key.companyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, companyId);
        }
    }
}
