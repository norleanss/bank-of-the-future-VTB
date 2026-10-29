package ru.creditconveyor.application;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentContentRepository extends JpaRepository<DocumentContent, UUID> {
}
