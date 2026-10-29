package ru.creditconveyor.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Каждая заявка истекает в своей короткой транзакции. Вызывать до транзакции действия: так {@code OFFER_EXPIRED}
 * сохраняется, даже если действие завершится 409.
 */
@Component
class OfferExpiration {

    private final LoanApplicationRepository applications;
    private final Clock clock;

    OfferExpiration(LoanApplicationRepository applications, Clock clock) {
        this.applications = applications;
        this.clock = clock;
    }

    @Transactional
    public boolean expireIfDue(UUID applicationId) {
        return applications.findById(applicationId)
                .filter(application -> application.expireOfferIfDue(clock.instant()))
                .map(application -> {
                    applications.save(application);
                    return true;
                })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<UUID> dueApplications() {
        return applications.findIdsWithDueOffers(LoanApplication.OFFERED, OfferStatus.ACTIVE, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<UUID> dueApplications(Collection<UUID> companyIds) {
        return applications.findIdsWithDueOffers(companyIds, LoanApplication.OFFERED, OfferStatus.ACTIVE, clock.instant());
    }
}
