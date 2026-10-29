package ru.creditconveyor.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Истекает офферы, даже если клиент не открывает заявку.
 */
@Component
class OfferExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OfferExpiryJob.class);

    private final OfferExpiration expiration;

    OfferExpiryJob(OfferExpiration expiration) {
        this.expiration = expiration;
    }

    @Scheduled(fixedDelayString = "${app.offer.expiry-check-interval}",
            initialDelayString = "${app.offer.expiry-check-interval}")
    void expireDueOffers() {
        int expired = runOnce();
        if (expired > 0) {
            log.info("Истёк срок предложений: {}", expired);
        }
    }

    int runOnce() {
        int expired = 0;
        for (UUID applicationId : expiration.dueApplications()) {
            try {
                if (expiration.expireIfDue(applicationId)) {
                    expired++;
                }
            } catch (OptimisticLockingFailureException e) {
                // Клиент в этот момент ответил на оффер — его действие важнее
                log.info("Заявка {} изменилась во время истечения предложения, пропускаем", applicationId);
            }
        }
        return expired;
    }
}
