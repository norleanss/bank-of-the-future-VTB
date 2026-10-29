package ru.creditconveyor.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import ru.creditconveyor.api.ApplicationActionDto;
import ru.creditconveyor.api.DocumentDto;
import ru.creditconveyor.api.DocumentTypeDto;
import ru.creditconveyor.identity.CompanyAccess;
import ru.creditconveyor.identity.CurrentUserProvider;

/**
 * Документы заявки. Клиент — только по своим заявкам и только когда банк ждёт документы ({@code UPLOAD_DOCUMENT}
 * в кнопках); сотрудник банка читает через {@link #list} и {@link #file} без проверки компании (роль проверяет
 * бэк-офис). Формат определяется по содержимому файла: имя и заголовок {@code Content-Type} задаёт клиент.
 */
@Service
public class ApplicationDocuments {

    /** Файл для скачивания. */
    public record File(String fileName, String mimeType, byte[] content) {

        /** Ответ со скачиванием: имя — в {@code Content-Disposition} (UTF-8, RFC 5987). */
        public ResponseEntity<Resource> asDownload() {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .contentLength(content.length)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .body(new ByteArrayResource(content));
        }
    }

    private static final Map<String, String> TYPE_LABELS = Map.of(
            "FINANCIAL_STATEMENT", "бухгалтерская отчётность",
            "BANK_STATEMENT", "выписка по счёту",
            "CHARTER", "устав",
            "OTHER", "другой документ");

    private final DocumentRepository documents;
    private final DocumentContentRepository contents;
    private final LoanApplicationRepository applications;
    private final CompanyAccess companyAccess;
    private final CurrentUserProvider currentUserProvider;
    private final DocumentProperties properties;
    private final Clock clock;

    ApplicationDocuments(DocumentRepository documents, DocumentContentRepository contents,
                         LoanApplicationRepository applications, CompanyAccess companyAccess,
                         CurrentUserProvider currentUserProvider, DocumentProperties properties, Clock clock) {
        this.documents = documents;
        this.contents = contents;
        this.applications = applications;
        this.companyAccess = companyAccess;
        this.currentUserProvider = currentUserProvider;
        this.properties = properties;
        this.clock = clock;
    }

    /** Для бэк-офиса: старые первыми. */
    @Transactional(readOnly = true)
    public List<DocumentDto> list(UUID applicationId) {
        return documents.findByApplicationIdOrderByUploadedAtAscIdAsc(applicationId).stream().map(this::toDto).toList();
    }

    /** Для бэк-офиса; документ другой заявки — пусто. */
    @Transactional(readOnly = true)
    public Optional<File> file(UUID applicationId, UUID documentId) {
        return documents.findByIdAndApplicationId(documentId, applicationId)
                .map(document -> new File(document.getFileName(), document.getMimeType(),
                        contents.findById(document.getId()).orElseThrow().getContent()));
    }

    @Transactional(readOnly = true)
    List<DocumentDto> listOwn(UUID applicationId) {
        requireOwn(applicationId);
        return list(applicationId);
    }

    @Transactional(readOnly = true)
    File fileOwn(UUID applicationId, UUID documentId) {
        requireOwn(applicationId);
        return file(applicationId, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден"));
    }

    /** Заявка блокируется на время загрузки: две параллельные загрузки не превысят лимит документов. */
    @Transactional
    DocumentDto upload(UUID applicationId, MultipartFile file, DocumentTypeDto documentType) {
        requireOwn(applicationId);
        LoanApplication application = applications.lockById(applicationId).orElseThrow();
        if (!ClientView.availableActions(application.getStatus()).contains(ApplicationActionDto.UPLOAD_DOCUMENT)) {
            throw InvalidStatusTransitionException.action("загрузить документ", application.getStatus());
        }
        byte[] content = bytes(file);
        if (content.length == 0) {
            throw invalidFile("Файл пустой");
        }
        if (content.length > properties.maxSize().toBytes()) {
            throw tooLarge();
        }
        String mimeType = detectType(content);
        if (mimeType == null || !properties.allowedTypes().contains(mimeType)) {
            throw invalidFile("Допустимые форматы: PDF, JPG, PNG");
        }
        if (documents.countByApplicationId(applicationId) >= properties.maxPerApplication()) {
            throw invalidFile("К заявке можно приложить не больше " + properties.maxPerApplication() + " документов");
        }
        ApplicationDocument document = documents.save(new ApplicationDocument(applicationId,
                documentType.getValue(), fileName(file.getOriginalFilename()), mimeType, content.length,
                sha256(content), currentUserProvider.currentUser().id(), clock.instant()));
        contents.save(new DocumentContent(document.getId(), content));
        return toDto(document);
    }

    /**
     * Документы к ответу на запрос: только загруженные к этой заявке и ещё не приложенные; запрошенные типы — все.
     * Вызывается в транзакции ответа.
     */
    void attach(UUID applicationId, InfoRequest request, List<UUID> documentIds) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<UUID, ApplicationDocument> found = documents.findByApplicationIdAndIdIn(applicationId, documentIds).stream()
                .collect(Collectors.toMap(ApplicationDocument::getId, document -> document));
        for (int i = 0; i < documentIds.size(); i++) {
            ApplicationDocument document = found.get(documentIds.get(i));
            if (document == null || document.isAttached()) {
                errors.put("documentIds[" + i + "]", "Документ не найден или уже приложен к другому ответу");
            }
        }
        Set<String> attachedTypes = found.values().stream().map(ApplicationDocument::getDocumentType)
                .collect(Collectors.toSet());
        List<String> missing = request.getRequestedDocuments().stream()
                .filter(type -> !attachedTypes.contains(type))
                .map(type -> TYPE_LABELS.getOrDefault(type, type))
                .toList();
        if (!missing.isEmpty() && errors.isEmpty()) {
            errors.put("documentIds", "Приложите документы: " + String.join(", ", missing));
        }
        if (!errors.isEmpty()) {
            throw new InvalidApplicationException("Приложите документы, которые запросил банк", errors);
        }
        found.values().forEach(document -> document.attachTo(request.getId()));
    }

    private LoanApplication requireOwn(UUID applicationId) {
        UUID companyId = applications.findCompanyIdById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
        companyAccess.checkAccess(companyId);
        return applications.findById(applicationId).orElseThrow();
    }

    private DocumentDto toDto(ApplicationDocument document) {
        return new DocumentDto(
                        document.getId(),
                        DocumentTypeDto.fromValue(document.getDocumentType()),
                        document.getFileName(),
                        document.getMimeType(),
                        Math.toIntExact(document.getSizeBytes()),
                        OffsetDateTime.ofInstant(document.getUploadedAt(), clock.getZone()))
                .infoRequestId(document.getInfoRequestId());
    }

    /** По сигнатуре в начале файла: PDF, JPEG, PNG. */
    static String detectType(byte[] content) {
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return "application/pdf";
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        return null;
    }

    /** Без пути и управляющих символов, не длиннее 255. */
    static String fileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (name.isEmpty()) {
            return "document";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(content, signature.length), toBytes(signature));
    }

    private static byte[] toBytes(int[] values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать загруженный файл", e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InvalidApplicationException invalidFile(String message) {
        return new InvalidApplicationException("Документ не загружен", Map.of("file", message));
    }

    private ErrorResponseException tooLarge() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "Файл больше " + properties.maxSize().toMegabytes() + " МБ");
        problem.setTitle("Файл слишком большой");
        return new ErrorResponseException(HttpStatus.CONTENT_TOO_LARGE, problem, null);
    }
}
