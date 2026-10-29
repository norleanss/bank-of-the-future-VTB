package ru.creditconveyor.identity;

import java.util.List;
import java.util.UUID;

/**
 * Клиент работает только со своими компаниями — все такие проверки идут здесь. Список проверяется по базе
 * при каждом запросе, поэтому изменения действуют без перевыпуска токена.
 */
public interface CompanyAccess {

    /** В порядке переключателя; не клиенту — {@code AccessDeniedException}. */
    List<UUID> companyIds();

    /** Компании нет в списке пользователя — {@code AccessDeniedException} (403). */
    void checkAccess(UUID companyId);
}
