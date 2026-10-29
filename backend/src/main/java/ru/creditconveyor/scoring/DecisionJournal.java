package ru.creditconveyor.scoring;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Решения сотрудников пишутся в тот же журнал, что и решения политики, — в транзакции смены статуса.
 */
@Service
@Transactional
public class DecisionJournal {

    private final DecisionRecordRepository decisions;
    private final Clock clock;

    DecisionJournal(DecisionRecordRepository decisions, Clock clock) {
        this.decisions = decisions;
        this.clock = clock;
    }

    public void record(UUID applicationId, EmployeeDecision decision) {
        decisions.save(new DecisionRecord(applicationId, decision, clock.instant()));
    }
}
