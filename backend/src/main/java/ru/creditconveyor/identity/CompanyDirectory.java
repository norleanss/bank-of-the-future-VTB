package ru.creditconveyor.identity;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Реквизиты компании для фоновой обработки заявки и бэк-офиса — без проверки доступа текущего пользователя.
 */
@Service
@Transactional(readOnly = true)
public class CompanyDirectory {

    private final CompanyRepository companies;
    private final Clock clock;

    CompanyDirectory(CompanyRepository companies, Clock clock) {
        this.companies = companies;
        this.clock = clock;
    }

    public Optional<String> innOf(UUID companyId) {
        return companies.findById(companyId).map(Company::getInn);
    }

    public Optional<CompanyProfile> profile(UUID companyId) {
        return companies.findById(companyId).map(company -> CompanyProfile.of(company, LocalDate.now(clock)));
    }
}
