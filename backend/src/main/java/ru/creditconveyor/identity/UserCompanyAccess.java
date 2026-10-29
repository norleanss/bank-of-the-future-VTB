package ru.creditconveyor.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ru.creditconveyor.api.RoleDto;

@Component
@Transactional(readOnly = true)
class UserCompanyAccess implements CompanyAccess {

    private final CurrentUserProvider currentUserProvider;
    private final UserCompanyLinkRepository links;

    UserCompanyAccess(CurrentUserProvider currentUserProvider, UserCompanyLinkRepository links) {
        this.currentUserProvider = currentUserProvider;
        this.links = links;
    }

    @Override
    public List<UUID> companyIds() {
        return links.findByUserIdOrderBySortOrder(currentClient().id()).stream()
                .map(UserCompanyLink::getCompanyId)
                .toList();
    }

    @Override
    public void checkAccess(UUID companyId) {
        if (!links.existsByUserIdAndCompanyId(currentClient().id(), companyId)) {
            throw new AccessDeniedException("Нет доступа к данным этой компании");
        }
    }

    private CurrentUser currentClient() {
        CurrentUser user = currentUserProvider.currentUser();
        if (user.role() != RoleDto.CLIENT) {
            throw new AccessDeniedException("Раздел доступен только клиенту банка");
        }
        return user;
    }
}
