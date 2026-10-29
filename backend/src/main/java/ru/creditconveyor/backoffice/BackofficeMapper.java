package ru.creditconveyor.backoffice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.BackofficeApplicationSummaryDto;
import ru.creditconveyor.api.ProductCodeDto;
import ru.creditconveyor.api.ReasonDto;
import ru.creditconveyor.application.ApplicationRegistry;
import ru.creditconveyor.identity.CompanyProfile;
import ru.creditconveyor.scoring.ScoringReports;

final class BackofficeMapper {

    private BackofficeMapper() {
    }

    static BackofficeApplicationSummaryDto toSummaryDto(ApplicationRegistry.QueueEntry entry, CompanyProfile company,
                                                        ScoringReports.Score score, ZoneId zone) {
        return new BackofficeApplicationSummaryDto(
                        entry.id(),
                        entry.number(),
                        ApplicationStatusDto.fromValue(entry.status().name()),
                        company.name(),
                        company.inn())
                .productCode(entry.productCode() != null ? ProductCodeDto.fromValue(entry.productCode()) : null)
                .requestedAmount(money(entry.requestedAmount()))
                .score(score != null ? score.score() : null)
                .grade(score != null ? BackofficeApplicationSummaryDto.GradeEnum.fromValue(score.grade()) : null)
                .reasons(entry.reasons().stream().map(reason -> new ReasonDto(reason.code(), reason.message()).advice(reason.advice()))
                        .toList())
                .submittedAt(at(entry.submittedAt(), zone))
                .waitingSince(at(entry.waitingSince(), zone));
    }

    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant != null ? OffsetDateTime.ofInstant(instant, zone) : null;
    }

    private static String money(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : null;
    }
}
