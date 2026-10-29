package ru.creditconveyor.scoring;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.ScoringDto;
import ru.creditconveyor.api.ScoringFactorDto;

/**
 * Балл последней проверки заявки: с разбивкой — для карточки, без неё — для очереди.
 */
@Service
@Transactional(readOnly = true)
public class ScoringReports {

    public record Score(int score, String grade) {
    }

    private final ScoringRecordRepository scorings;

    ScoringReports(ScoringRecordRepository scorings) {
        this.scorings = scorings;
    }

    public Optional<ScoringDto> latest(UUID applicationId) {
        return scorings.findFirstByApplicationIdOrderByCreatedAtDesc(applicationId).map(ScoringReports::toDto);
    }

    public Map<UUID, Score> latestScores(Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        return scorings.findByApplicationIdIn(applicationIds).stream()
                .collect(Collectors.toMap(ScoringRecord::getApplicationId, record -> record,
                        BinaryOperator.maxBy(Comparator.comparing(ScoringRecord::getCreatedAt))))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new Score(entry.getValue().getScore(), entry.getValue().getGrade())));
    }

    private static ScoringDto toDto(ScoringRecord record) {
        return new ScoringDto(record.getScore(), record.getFactors().stream()
                        .map(factor -> new ScoringFactorDto(factor.getFactor().name(), factor.getPoints())
                                .inputValue(factor.getInputValue())
                                .maxPoints(factor.getMaxPoints())
                                .explanation(factor.getExplanation()))
                        .toList())
                .grade(ScoringDto.GradeEnum.fromValue(record.getGrade()));
    }
}
