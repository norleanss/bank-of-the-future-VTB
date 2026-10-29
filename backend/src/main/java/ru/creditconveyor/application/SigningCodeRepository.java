package ru.creditconveyor.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SigningCodeRepository extends JpaRepository<SigningCode, UUID> {

    Optional<SigningCode> findByApplicationIdAndStatus(UUID applicationId, String status);

    boolean existsByApplicationIdAndStatus(UUID applicationId, String status);
}
