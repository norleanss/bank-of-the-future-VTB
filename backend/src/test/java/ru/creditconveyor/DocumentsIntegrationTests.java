package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Документы заявки: загрузка, когда банк их запросил, проверка формата по содержимому и размера, приложение
 * к ответу на запрос, скачивание клиентом и сотрудником; доступ и CORS.
 */
class DocumentsIntegrationTests extends IntegrationTest {

    private static final Set<String> IN_PROGRESS = Set.of("SUBMITTED", "ENRICHMENT", "SCORING");
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
    private static final String MOROZOV_LINE = """
            {"companyId": "%s", "productCode": "CREDIT_LINE", "purpose": "WORKING_CAPITAL", "requestedAmount": "1000000.00",
             "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES",
             "financials": {"annualRevenue": "12000000.00", "annualExpenses": "9000000.00", "monthlyDebtPayment": "50000.00"}}
            """.formatted(MOROZOV);

    @Test
    void clientAttachesRequestedDocumentAndUnderwriterDownloadsIt() {
        String id = documentsRequested();

        // Формат — по содержимому: имя и Content-Type задаёт клиент
        MvcTestResult report = upload(CLIENT, id, "../../отчётность 2025.pdf", "text/plain", PDF, "FINANCIAL_STATEMENT");
        var uploaded = assertThat(report).hasStatus(HttpStatus.CREATED).bodyJson();
        uploaded.extractingPath("$.fileName").isEqualTo("отчётность 2025.pdf");
        uploaded.extractingPath("$.mimeType").isEqualTo("application/pdf");
        uploaded.extractingPath("$.sizeBytes").isEqualTo(PDF.length);
        String documentId = read(report, "$.id");
        clock.advance(Duration.ofMinutes(1));
        String statementId = read(upload(CLIENT, id, "statement.pdf", "application/pdf", PDF, "BANK_STATEMENT"), "$.id");
        assertThat(get(CLIENT, "/applications/" + id + "/documents")).hasStatusOk().bodyJson()
                .extractingPath("$[*].documentType").asArray().containsExactly("FINANCIAL_STATEMENT", "BANK_STATEMENT");

        // Запрошена отчётность — выписки недостаточно; чужой документ — ошибка по индексу
        String requestId = read(get(CLIENT, "/applications/" + id), "$.openInfoRequest.id");
        String response = "/applications/" + id + "/info-requests/" + requestId + "/response";
        assertThat(post(CLIENT, response, "{\"documentIds\": [\"%s\"]}".formatted(statementId)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].message")
                .isEqualTo("Приложите документы: бухгалтерская отчётность");
        assertThat(post(CLIENT, response, "{\"documentIds\": [\"%s\", \"00000000-0000-4000-8000-000000000000\"]}"
                .formatted(documentId))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0].field").isEqualTo("documentIds[1]");

        assertThat(post(CLIENT, response, "{\"comment\": \"Отчётность за 2025 год\", \"documentIds\": [\"%s\"]}"
                .formatted(documentId))).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("MANUAL_REVIEW");

        var card = assertThat(get(UNDERWRITER, "/backoffice/applications/" + id)).hasStatusOk().bodyJson();
        card.extractingPath("$.documents[*].id").asArray().containsExactly(documentId, statementId);
        card.extractingPath("$.documents[0].infoRequestId").isEqualTo(requestId);
        card.doesNotHavePath("$.documents[1].infoRequestId");

        MvcTestResult file = get(UNDERWRITER, "/backoffice/applications/" + id + "/documents/" + documentId + "/content");
        assertThat(file).hasStatusOk().hasContentType("application/pdf");
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;").contains("filename*=UTF-8''");
        assertThat(get(CLIENT, "/applications/" + id + "/documents/" + documentId + "/content")).hasStatusOk();

        // Доступ: чужой клиент, клиент в бэк-офисе, андеррайтер в кабинете клиента, документ другой заявки
        assertThat(get(OTHER_CLIENT, "/applications/" + id + "/documents")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(OTHER_CLIENT, "/applications/" + id + "/documents/" + documentId + "/content"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(CLIENT, "/backoffice/applications/" + id + "/documents/" + documentId + "/content"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(get(UNDERWRITER, "/applications/" + id + "/documents")).hasStatus(HttpStatus.FORBIDDEN);
        String other = read(post(CLIENT, "/applications", MOROZOV_LINE), "$.id");
        assertThat(get(CLIENT, "/applications/" + other + "/documents/" + documentId + "/content"))
                .hasStatus(HttpStatus.NOT_FOUND);
        // После ответа банк документов не ждёт
        assertThat(upload(CLIENT, id, "more.pdf", "application/pdf", PDF, "OTHER")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void wrongFilesAreRejected() {
        String id = documentsRequested();

        assertThat(upload(CLIENT, id, "fake.pdf", "application/pdf", "не PDF".getBytes(StandardCharsets.UTF_8), "OTHER"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].field").isEqualTo("file");
        assertThat(upload(CLIENT, id, "empty.pdf", "application/pdf", new byte[0], "OTHER"))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors[0].message").isEqualTo("Файл пустой");
        byte[] large = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        assertThat(upload(CLIENT, id, "large.pdf", "application/pdf", large, "OTHER"))
                .hasStatus(HttpStatus.CONTENT_TOO_LARGE).bodyJson().extractingPath("$.title").isEqualTo("Файл слишком большой");
        assertThat(upload(CLIENT, id, "scan.png", "image/png", PNG, "CHARTER")).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.mimeType").isEqualTo("image/png");

        String draft = read(post(CLIENT, "/applications", MOROZOV_LINE), "$.id");
        assertThat(upload(CLIENT, draft, "draft.pdf", "application/pdf", PDF, "OTHER")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void missingDocumentTypeAndLongFileName() {
        String id = documentsRequested();

        var missingType = assertThat(mvc.post().uri("/applications/" + id + "/documents")
                .header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).multipart()
                .file(new MockMultipartFile("file", "report.pdf", "application/pdf", PDF)).exchange())
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson();
        missingType.extractingPath("$.detail").isEqualTo("Некоторые поля заполнены неверно");
        missingType.extractingPath("$.errors[0].field").isEqualTo("documentType");
        missingType.extractingPath("$.errors[0].message").isEqualTo("Обязательное поле");

        // Имя длиннее колонки базы сохраняется обрезанным до 255 символов
        String longName = "бухгалтерская отчётность ".repeat(12) + ".pdf";
        assertThat(upload(CLIENT, id, longName, "application/pdf", PDF, "FINANCIAL_STATEMENT"))
                .hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.fileName")
                .isEqualTo(longName.substring(0, 255));
    }

    /** Заявка блокируется на время загрузки: параллельные загрузки не превышают лимит документов. */
    @Test
    void parallelUploadsRespectDocumentLimit() throws Exception {
        String id = documentsRequested();
        String token = bearer(CLIENT);
        List<Callable<Integer>> uploads = IntStream.range(0, 24)
                .<Callable<Integer>>mapToObj(i -> () -> mvc.post().uri("/applications/" + id + "/documents")
                        .header(HttpHeaders.AUTHORIZATION, token).multipart()
                        .file(new MockMultipartFile("file", "doc" + i + ".pdf", "application/pdf", PDF))
                        .param("documentType", "OTHER").exchange().getResponse().getStatus())
                .toList();
        List<Integer> statuses;
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            statuses = executor.invokeAll(uploads).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(20);
        assertThat(statuses).filteredOn(status -> status != 201).containsOnly(400);
        List<String> documents = JsonPath.read(new String(get(CLIENT, "/applications/" + id + "/documents").getResponse()
                .getContentAsByteArray(), StandardCharsets.UTF_8), "$[*].id");
        assertThat(documents).hasSize(20);
    }

    @Test
    void corsAllowsConfiguredFrontendOnly() {
        assertThat(mvc.options().uri("/applications")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .hasStatusOk()
                .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
        assertThat(mvc.options().uri("/applications")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/products").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .hasStatusOk()
                .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000");
    }

    /** Морозов на ручной проверке; андеррайтер запросил отчётность. */
    private String documentsRequested() {
        String id = read(post(CLIENT, "/applications", MOROZOV_LINE), "$.id");
        assertThat(post(CLIENT, "/applications/" + id + "/submit", null)).hasStatus(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(15))
                .until(() -> !IN_PROGRESS.contains(read(get(CLIENT, "/applications/" + id), "$.status")));
        assertThat(post(UNDERWRITER, "/backoffice/applications/" + id + "/info-requests", """
                {"message": "Пришлите бухгалтерскую отчётность за 2025 год", "requestedDocuments": ["FINANCIAL_STATEMENT"]}
                """)).hasStatus(HttpStatus.CREATED);
        assertThat(get(CLIENT, "/applications/" + id)).bodyJson().extractingPath("$.nextStepHint")
                .isEqualTo("Банку нужны дополнительные сведения — приложите документы и ответьте на запрос");
        return id;
    }

    private MvcTestResult upload(String login, String id, String fileName, String contentType, byte[] content,
                                 String documentType) {
        return mvc.post().uri("/applications/" + id + "/documents")
                .header(HttpHeaders.AUTHORIZATION, bearer(login))
                .multipart()
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .param("documentType", documentType)
                .exchange();
    }

    private MvcTestResult get(String login, String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(login)).exchange();
    }

    private MvcTestResult post(String login, String uri, String json) {
        var request = mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(login));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), path);
    }
}
