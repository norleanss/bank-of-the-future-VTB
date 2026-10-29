package ru.creditconveyor.enrichment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface EnrichmentRepository extends JpaRepository<Enrichment, UUID> {

    Optional<Enrichment> findFirstByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
