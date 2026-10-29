package ru.creditconveyor.backoffice;

import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.BackofficeApi;
import ru.creditconveyor.api.BackofficeApplicationDetailDto;
import ru.creditconveyor.api.BackofficeApplicationSummaryPageDto;
import ru.creditconveyor.api.CreateInfoRequestDto;
import ru.creditconveyor.api.InfoRequestDto;
import ru.creditconveyor.api.ManualDecisionRequestDto;

/**
 * Методы переопределены явно: {@code @PreAuthorize} на классе не действует на унаследованные default-методы.
 */
@RestController
@PreAuthorize("hasRole('UNDERWRITER')")
class BackofficeController implements BackofficeApi {

    private final BackofficeService backoffice;

    BackofficeController(BackofficeService backoffice) {
        this.backoffice = backoffice;
    }

    @Override
    public ResponseEntity<BackofficeApplicationSummaryPageDto> listBackofficeApplications(
            String queue, ApplicationStatusDto status, Integer page, Integer size) {
        return ResponseEntity.ok(backoffice.list(queue, status, page, size));
    }

    @Override
    public ResponseEntity<BackofficeApplicationDetailDto> getBackofficeApplication(UUID id) {
        return ResponseEntity.ok(backoffice.card(id));
    }

    @Override
    public ResponseEntity<Resource> downloadBackofficeDocument(UUID id, UUID documentId) {
        return backoffice.document(id, documentId).asDownload();
    }

    @Override
    public ResponseEntity<ApplicationDto> makeDecision(UUID id, ManualDecisionRequestDto request) {
        return ResponseEntity.ok(backoffice.decide(id, request));
    }

    @Override
    public ResponseEntity<InfoRequestDto> createInfoRequest(UUID id, CreateInfoRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(backoffice.requestInfo(id, request));
    }
}
