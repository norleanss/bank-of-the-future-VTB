package ru.creditconveyor.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentRepository extends JpaRepository<ApplicationDocument, UUID> {

    List<ApplicationDocument> findByApplicationIdOrderByUploadedAtAscIdAsc(UUID applicationId);

    Optional<ApplicationDocument> findByIdAndApplicationId(UUID id, UUID applicationId);

    List<ApplicationDocument> findByApplicationIdAndIdIn(UUID applicationId, Collection<UUID> ids);

    long countByApplicationId(UUID applicationId);
}
