package ru.creditconveyor.scoring;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ScoringRecordRepository extends JpaRepository<ScoringRecord, UUID> {

    Optional<ScoringRecord> findFirstByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    List<ScoringRecord> findByApplicationIdIn(Collection<UUID> applicationIds);
}
