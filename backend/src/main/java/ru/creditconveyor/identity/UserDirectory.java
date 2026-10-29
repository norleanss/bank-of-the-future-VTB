package ru.creditconveyor.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Имена пользователей — для истории заявки в бэк-офисе.
 */
@Service
@Transactional(readOnly = true)
public class UserDirectory {

    private final UserAccountRepository users;

    UserDirectory(UserAccountRepository users) {
        this.users = users;
    }

    public Map<UUID, String> fullNames(Collection<UUID> userIds) {
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }
}
