package ru.creditconveyor.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserCompanyLinkRepository extends JpaRepository<UserCompanyLink, UserCompanyLink.Key> {

    List<UserCompanyLink> findByUserIdOrderBySortOrder(UUID userId);

    boolean existsByUserIdAndCompanyId(UUID userId, UUID companyId);
}
