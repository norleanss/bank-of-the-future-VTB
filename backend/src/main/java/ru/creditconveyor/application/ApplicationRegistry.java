package ru.creditconveyor.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.InfoRequestDto;
import ru.creditconveyor.api.StatusHistoryItemDto;
import ru.creditconveyor.identity.UserDirectory;

/**
 * Заявки всех клиентов для бэк-офиса, без проверки доступа к компании: роль проверяет вызывающий модуль.
 */
@Service
@Transactional(readOnly = true)
public class ApplicationRegistry {

    /** Строка очереди; {@code waitingSince} — с какого момента заявка в текущем статусе. */
    public record QueueEntry(
            UUID id,
            String number,
            ApplicationStatus status,
            UUID companyId,
            @Nullable String productCode,
            @Nullable BigDecimal requestedAmount,
            List<DecisionReason> reasons,
            @Nullable Instant submittedAt,
            Instant waitingSince) {
    }

    /** Карточка: заявка, все запросы сведений и история с именами сотрудников. */
    public record Card(ApplicationDto application, List<InfoRequestDto> infoRequests,
                       List<StatusHistoryItemDto> history) {
    }

    private final LoanApplicationRepository applications;
    private final UserDirectory users;
    private final Clock clock;

    ApplicationRegistry(LoanApplicationRepository applications, UserDirectory users, Clock clock) {
        this.applications = applications;
        this.users = users;
        this.clock = clock;
    }

    /** Дольше всех ждущие — первыми. */
    public Page<QueueEntry> waiting(Collection<ApplicationStatus> statuses, int page, int size) {
        return find(statuses, PageRequest.of(page, size, Sort.by("statusChangedAt", "id")));
    }

    /** Недавно отправленные — первыми. */
    public Page<QueueEntry> submitted(Collection<ApplicationStatus> statuses, int page, int size) {
        return find(statuses, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.asc("id"))));
    }

    public Optional<Card> card(UUID id) {
        return applications.findById(id).map(application -> {
            Map<UUID, String> names = users.fullNames(application.getHistory().stream()
                    .filter(entry -> entry.getActorType() == ActorType.EMPLOYEE)
                    .map(StatusHistoryEntry::getActorId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList());
            return new Card(
                    ApplicationMapper.toDto(application, clock.getZone()),
                    application.getInfoRequests().stream()
                            .map(request -> ApplicationMapper.toDto(request, clock.getZone()))
                            .toList(),
                    application.getHistory().stream()
                            .map(entry -> ApplicationMapper.toDto(entry, clock.getZone())
                                    .actorName(entry.getActorType() == ActorType.EMPLOYEE
                                            ? names.get(entry.getActorId())
                                            : null))
                            .toList());
        });
    }

    private Page<QueueEntry> find(Collection<ApplicationStatus> statuses, PageRequest page) {
        return applications.findByStatusIn(statuses, page).map(application -> new QueueEntry(
                application.getId(),
                application.getNumber(),
                application.getStatus(),
                application.getCompanyId(),
                application.getProductCode(),
                application.getRequestedAmount(),
                application.getReasons(),
                application.getSubmittedAt(),
                application.getStatusChangedAt()));
    }
}
