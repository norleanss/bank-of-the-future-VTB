package ru.creditconveyor.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import ru.creditconveyor.api.CompanyApi;
import ru.creditconveyor.api.CompanyDto;

/**
 * Чужая и несуществующая компания дают одинаковый 403: существование компании не раскрываем.
 */
@RestController
class CompanyController implements CompanyApi {

    private final ClientCompanies clientCompanies;

    CompanyController(ClientCompanies clientCompanies) {
        this.clientCompanies = clientCompanies;
    }

    @Override
    public ResponseEntity<List<CompanyDto>> listCompanies() {
        return ResponseEntity.ok(clientCompanies.currentClientCompanies().stream()
                .map(CompanyProfile::toDto)
                .toList());
    }

    @Override
    public ResponseEntity<CompanyDto> getCompany(UUID id) {
        return ResponseEntity.ok(clientCompanies.companyProfile(id).toDto());
    }
}
