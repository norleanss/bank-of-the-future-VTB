package ru.creditconveyor.application;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;

import ru.creditconveyor.api.ApplicationCreateRequestDto;
import ru.creditconveyor.api.ApplicationDto;
import ru.creditconveyor.api.ApplicationPatchRequestDto;
import ru.creditconveyor.api.ApplicationSummaryPageDto;
import ru.creditconveyor.api.ApplicationsApi;
import ru.creditconveyor.api.ConfirmSigningRequestDto;
import ru.creditconveyor.api.DeclineOfferRequestDto;
import ru.creditconveyor.api.DocumentDto;
import ru.creditconveyor.api.DocumentTypeDto;
import ru.creditconveyor.api.InfoRequestResponseDto;
import ru.creditconveyor.api.SigningCodeResponseDto;
import ru.creditconveyor.api.StatusHistoryItemDto;

/**
 * Все методы переопределены явно: {@code @PreAuthorize} на классе не действует на унаследованные default-методы.
 */
@RestController
@PreAuthorize("hasRole('CLIENT')")
class ApplicationController implements ApplicationsApi {

    private final ApplicationService service;
    private final ApplicationDocuments documents;

    ApplicationController(ApplicationService service, ApplicationDocuments documents) {
        this.documents = documents;
        this.service = service;
    }

    @Override
    public ResponseEntity<ApplicationDto> createApplication(ApplicationCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @Override
    public ResponseEntity<ApplicationSummaryPageDto> listApplications(UUID companyId, Integer page, Integer size) {
        return ResponseEntity.ok(service.list(companyId, page, size));
    }

    @Override
    public ResponseEntity<ApplicationDto> getApplication(UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @Override
    public ResponseEntity<ApplicationDto> updateApplication(UUID id, ApplicationPatchRequestDto request) {
        return ResponseEntity.ok(service.update(id, request, MergePatchBodyAdvice.currentDocument()));
    }

    @Override
    public ResponseEntity<ApplicationDto> submitApplication(UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.submit(id));
    }

    @Override
    public ResponseEntity<ApplicationDto> cancelApplication(UUID id) {
        return ResponseEntity.ok(service.cancel(id));
    }

    @Override
    public ResponseEntity<List<StatusHistoryItemDto>> getApplicationHistory(UUID id) {
        return ResponseEntity.ok(service.history(id));
    }

    @Override
    public ResponseEntity<ApplicationDto> acceptOffer(UUID id) {
        return ResponseEntity.ok(service.acceptOffer(id));
    }

    @Override
    public ResponseEntity<ApplicationDto> declineOffer(UUID id, DeclineOfferRequestDto request) {
        return ResponseEntity.ok(service.declineOffer(id, request != null ? request.getComment() : null));
    }

    @Override
    public ResponseEntity<ApplicationDto> respondInfoRequest(UUID id, UUID requestId, InfoRequestResponseDto request) {
        return ResponseEntity.ok(service.respondInfoRequest(id, requestId,
                request != null ? request.getComment() : null,
                request != null && request.getDocumentIds() != null ? request.getDocumentIds() : List.of()));
    }

    @Override
    public ResponseEntity<SigningCodeResponseDto> requestSigningCode(UUID id) {
        return ResponseEntity.ok(service.requestSigningCode(id));
    }

    @Override
    public ResponseEntity<ApplicationDto> confirmSigning(UUID id, ConfirmSigningRequestDto request) {
        return ResponseEntity.ok(service.confirmSigning(id, request.getCode()));
    }

    @Override
    public ResponseEntity<List<DocumentDto>> listDocuments(UUID id) {
        return ResponseEntity.ok(documents.listOwn(id));
    }

    @Override
    public ResponseEntity<DocumentDto> uploadDocument(UUID id, MultipartFile file, DocumentTypeDto documentType) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documents.upload(id, file, documentType));
    }

    @Override
    public ResponseEntity<Resource> downloadDocument(UUID id, UUID documentId) {
        return documents.fileOwn(id, documentId).asDownload();
    }
}
