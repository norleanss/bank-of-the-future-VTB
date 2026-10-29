package ru.creditconveyor.scoring;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DecisionRecordRepository extends JpaRepository<DecisionRecord, UUID> {
}
