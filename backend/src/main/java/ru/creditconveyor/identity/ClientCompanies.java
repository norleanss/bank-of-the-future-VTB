package ru.creditconveyor.identity;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Компании текущего клиента. Регистрации нет: заёмщик — действующий клиент, компании уже в профиле.
 */
@Service
@Transactional(readOnly = true)
public class ClientCompanies {

    private final CompanyAccess companyAccess;
    private final CompanyRepository companies;
    private final Clock clock;

    ClientCompanies(CompanyAccess companyAccess, CompanyRepository companies, Clock clock) {
        this.companyAccess = companyAccess;
        this.companies = companies;
        this.clock = clock;
    }

    public List<CompanyProfile> currentClientCompanies() {
        return companyAccess.companyIds().stream().map(this::profile).toList();
    }

    public CompanyProfile companyProfile(UUID companyId) {
        companyAccess.checkAccess(companyId);
        return profile(companyId);
    }

    private CompanyProfile profile(UUID companyId) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Компания " + companyId + " не найдена"));
        return CompanyProfile.of(company, LocalDate.now(clock));
    }
}
