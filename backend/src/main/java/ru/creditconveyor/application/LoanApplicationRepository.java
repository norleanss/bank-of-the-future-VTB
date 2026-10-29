package ru.creditconveyor.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    Page<LoanApplication> findByCompanyIdIn(Collection<UUID> companyIds, Pageable pageable);

    Page<LoanApplication> findByStatusIn(Collection<ApplicationStatus> statuses, Pageable pageable);

    /** Блокировка строки заявки до конца транзакции: проверка «сколько документов» и загрузка — без гонки. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LoanApplication a WHERE a.id = :id")
    Optional<LoanApplication> lockById(UUID id);

    /** Для проверки доступа без загрузки самой заявки. */
    @Query("SELECT a.companyId FROM LoanApplication a WHERE a.id = :id")
    Optional<UUID> findCompanyIdById(UUID id);

    @Query(value = "SELECT nextval('application.application_number_seq')", nativeQuery = true)
    long nextNumber();

    @Query("""
            SELECT a.id FROM LoanApplication a JOIN a.offers o
            WHERE a.status IN :offered AND o.status = :active AND o.expiresAt <= :now""")
    List<UUID> findIdsWithDueOffers(Collection<ApplicationStatus> offered, OfferStatus active, Instant now);

    @Query("""
            SELECT a.id FROM LoanApplication a JOIN a.offers o
            WHERE a.companyId IN :companyIds AND a.status IN :offered AND o.status = :active AND o.expiresAt <= :now""")
    List<UUID> findIdsWithDueOffers(Collection<UUID> companyIds, Collection<ApplicationStatus> offered,
                                    OfferStatus active, Instant now);
}
