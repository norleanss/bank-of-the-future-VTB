package ru.creditconveyor.identity;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompanyRepository extends JpaRepository<Company, UUID> {
}
