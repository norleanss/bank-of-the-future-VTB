package ru.creditconveyor.identity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(schema = "identity", name = "companies")
class Company {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String inn;

    @Column(nullable = false)
    private String ogrn;

    @Column(name = "business_type", nullable = false)
    private String businessType;

    @Column(nullable = false)
    private String name;

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @Column(nullable = false)
    private String status;

    private String okved;

    private String region;

    protected Company() {
    }

    UUID getId() {
        return id;
    }

    String getInn() {
        return inn;
    }

    String getOgrn() {
        return ogrn;
    }

    String getBusinessType() {
        return businessType;
    }

    String getName() {
        return name;
    }

    LocalDate getRegistrationDate() {
        return registrationDate;
    }

    String getStatus() {
        return status;
    }

    String getOkved() {
        return okved;
    }

    String getRegion() {
        return region;
    }
}
