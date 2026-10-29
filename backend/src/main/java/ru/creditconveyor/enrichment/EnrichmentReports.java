package ru.creditconveyor.enrichment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.EnrichmentDto;
import ru.creditconveyor.api.SourceResultDto;

/**
 * Данные источников последней проверки заявки — для карточки андеррайтера.
 */
@Service
@Transactional(readOnly = true)
public class EnrichmentReports {

    private final EnrichmentRepository enrichments;
    private final Clock clock;

    EnrichmentReports(EnrichmentRepository enrichments, Clock clock) {
        this.enrichments = enrichments;
        this.clock = clock;
    }

    public Optional<EnrichmentDto> latest(UUID applicationId) {
        return enrichments.findFirstByApplicationIdOrderByCreatedAtDesc(applicationId).map(this::toDto);
    }

    private EnrichmentDto toDto(Enrichment enrichment) {
        return new EnrichmentDto(enrichment.getSources().stream()
                        .sorted(Comparator.comparing(SourceResult::getSource))
                        .map(this::toDto)
                        .toList())
                .taxDebtAmount(money(enrichment.getTaxDebtAmount()))
                .activeCreditsCount(enrichment.getActiveCreditsCount())
                .totalDebtAmount(money(enrichment.getTotalDebtAmount()))
                .maxOverdueDays12m(enrichment.getMaxOverdueDays12m());
    }

    private SourceResultDto toDto(SourceResult result) {
        return new SourceResultDto(
                        SourceResultDto.SourceEnum.fromValue(result.getSource().name()),
                        SourceResultDto.StatusEnum.fromValue(result.getStatus().name()))
                .receivedAt(result.getReceivedAt() != null
                        ? result.getReceivedAt().atZone(clock.getZone()).toOffsetDateTime()
                        : null);
    }

    private static String money(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : null;
    }
}
