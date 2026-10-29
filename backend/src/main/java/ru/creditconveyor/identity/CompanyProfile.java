package ru.creditconveyor.identity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import ru.creditconveyor.api.BusinessTypeDto;
import ru.creditconveyor.api.CompanyDto;
import ru.creditconveyor.api.CompanyStatusDto;

/**
 * Компания клиента; возраст бизнеса — на текущую дату.
 */
public record CompanyProfile(
        UUID id,
        String inn,
        String ogrn,
        BusinessTypeDto businessType,
        String name,
        LocalDate registrationDate,
        int businessAgeMonths,
        CompanyStatusDto status,
        String okved,
        String region) {

    static CompanyProfile of(Company company, LocalDate today) {
        return new CompanyProfile(
                company.getId(),
                company.getInn(),
                company.getOgrn(),
                BusinessTypeDto.fromValue(company.getBusinessType()),
                company.getName(),
                company.getRegistrationDate(),
                (int) ChronoUnit.MONTHS.between(company.getRegistrationDate(), today),
                CompanyStatusDto.fromValue(company.getStatus()),
                company.getOkved(),
                company.getRegion());
    }

    public CompanyDto toDto() {
        return new CompanyDto(id, inn, ogrn, businessType, name, registrationDate, status)
                .businessAgeMonths(businessAgeMonths)
                .okved(okved)
                .region(region);
    }
}
