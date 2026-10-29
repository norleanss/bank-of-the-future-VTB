package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.json.JsonMapper;

import ru.creditconveyor.api.ApplicationStatusDto;
import ru.creditconveyor.api.PurposeDto;

/**
 * Каталог и пример подбора сверяются с примерами контракта один в один.
 */
class ApiIntegrationTests extends IntegrationTest {

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void catalogMatchesContractExample() {
        MvcTestResult response = mvc.get().uri("/products").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange();

        assertThat(response).hasStatusOk();
        assertThat(body(response)).isEqualTo(ContractExamples.responseExample("/products", "get", "200"));
    }

    @Test
    void matchReproducesContractExample() {
        Object request = ContractExamples.requestExample("/products/match", "post");

        MvcTestResult response = mvc.post().uri("/products/match").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(request))
                .exchange();

        assertThat(response).hasStatusOk();
        assertThat(body(response)).isEqualTo(ContractExamples.responseExample("/products/match", "post", "200"));
    }

    @Test
    void youngBusinessIsOfferedGammaStart() {
        var body = assertThat(mvc.post().uri("/products/match").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"companyId": "%s", "purpose": "EQUIPMENT", "requestedAmount": "800000.00",
                         "term": 6, "termUnit": "MONTHS", "fundingMode": "TRANCHES"}
                        """.formatted(SOKOLOVA)))
                .hasStatusOk().bodyJson();
        body.extractingPath("$.items[0].product.code").isEqualTo("GAMMA_START");
        body.extractingPath("$.items[0].suitable").isEqualTo(true);
        body.extractingPath("$.items[0].recommended").isEqualTo(true);
        body.extractingPath("$.items[?(@.product.code == 'CREDIT_LINE')].reasons[0]").asArray()
                .containsExactly("Минимальный возраст бизнеса — 12 месяцев");
    }

    @Test
    void returnsProductByCode() {
        var response = assertThat(mvc.get().uri("/products/OVERDRAFT").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatusOk().bodyJson();
        response.extractingPath("$.termUnit").isEqualTo("DAYS");
        response.extractingPath("$.maxTerm").isEqualTo(60);
        response.extractingPath("$.maxTermWithSubscription").isEqualTo(90);
        response.extractingPath("$.subscriptionFeePercent").isEqualTo(1.0);
    }

    @Test
    void dictionariesAreTheContractExample() {
        MvcTestResult response = mvc.get().uri("/dictionaries").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange();

        assertThat(body(response)).isEqualTo(ContractExamples.responseExample("/dictionaries", "get", "200"));
    }

    @Test
    void returnsAllDictionaryLabelsWithoutEmptyFields() {
        var response = assertThat(mvc.get().uri("/dictionaries").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))).hasStatusOk().bodyJson();
        response.extractingPath("$.applicationStatuses[*].code").asArray()
                .hasSize(ApplicationStatusDto.values().length)
                .contains("OFFER_EXPIRED");
        response.extractingPath("$.applicationStatuses[?(@.code == 'OFFER_EXPIRED')].label").asArray()
                .containsExactly("Срок предложения истёк");
        response.extractingPath("$.purposes[*].code").asArray().hasSize(PurposeDto.values().length);
        // Поля без значения в ответе нет вовсе, а не null
        response.doesNotHavePath("$.fundingModes[0].description");
    }

    @Test
    void matchRequiresOwnCompany() {
        assertThat(mvc.post().uri("/products/match").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"companyId": "%s", "purpose": "CASH_GAP", "requestedAmount": "1500000.00",
                         "term": 2, "termUnit": "MONTHS", "fundingMode": "ON_DEMAND"}
                        """.formatted(FOREIGN_COMPANY)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- Ошибки 400: по-русски и с путями полей ---

    @Test
    void validatesMatchRequestWithFieldErrors() {
        var body = badRequest(mvc.post().uri("/products/match").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"companyId": "%s", "purpose": "CASH_GAP", "requestedAmount": "1 500 000",
                         "term": 0, "termUnit": "MONTHS", "fundingMode": "ON_DEMAND"}
                        """.formatted(SEVERNY_VETER)).exchange());
        body.extractingPath("$.title").isEqualTo("Ошибка валидации");
        body.extractingPath("$.errors[*].field").asArray().containsExactlyInAnyOrder("requestedAmount", "term");
    }

    @Test
    void tooLargeAmountIsValidationErrorNotServerError() {
        var body = badRequest(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\": \"%s\", \"requestedAmount\": \"100000000000000000.00\"}".formatted(SEVERNY_VETER))
                .exchange());
        body.extractingPath("$.errors[*].field").asArray().containsExactly("requestedAmount");
    }

    @Test
    void frameworkErrorsAreRussianProblems() {
        var notFound = assertThat(mvc.get().uri("/no-such-endpoint").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson();
        notFound.extractingPath("$.title").isEqualTo("Не найдено");
        notFound.extractingPath("$.detail").isEqualTo("Такого адреса нет в API");

        var notAllowed = assertThat(mvc.delete().uri("/products").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.ALLOW, "GET").bodyJson();
        notAllowed.extractingPath("$.title").isEqualTo("Метод не поддерживается");
        notAllowed.extractingPath("$.detail").isEqualTo("Метод DELETE для этого адреса не поддерживается. Допустимые: GET");

        var unsupported = assertThat(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.TEXT_PLAIN).content("заявка"))
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson();
        unsupported.extractingPath("$.title").isEqualTo("Формат не поддерживается");
        unsupported.extractingPath("$.detail").asString()
                .startsWith("Формат тела запроса text/plain не поддерживается. Допустимые: application/json");
    }

    @Test
    void unreadableBodyPointsToFieldInRussian() {
        var body = badRequest(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\": \"%s\", \"purpose\": \"VACATION\"}".formatted(SEVERNY_VETER))
                .exchange());
        body.extractingPath("$.detail").isEqualTo("Тело запроса не удалось прочитать: проверьте JSON и значения полей");
        body.extractingPath("$.errors[0].field").isEqualTo("purpose");
        body.extractingPath("$.errors[0].message").asString()
                .startsWith("Недопустимое значение. Допустимые: WORKING_CAPITAL");

        var nested = badRequest(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\": \"%s\", \"financials\": {\"annualRevenue\": 100}, \"term\": \"три\"}".formatted(SEVERNY_VETER))
                .exchange());
        nested.extractingPath("$.errors[0].field").asString().isIn("financials.annualRevenue", "term");

        var notJson = badRequest(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON).content("{не json").exchange());
        notJson.extractingPath("$.title").isEqualTo("Ошибка валидации");
    }

    @Test
    void wrongPathAndQueryParametersAreExplained() {
        var uuid = badRequest(mvc.get().uri("/applications/not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange());
        uuid.extractingPath("$.detail").isEqualTo("Некоторые параметры запроса заданы неверно");
        uuid.extractingPath("$.errors[0].field").isEqualTo("id");

        var code = badRequest(mvc.get().uri("/products/MORTGAGE").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange());
        code.extractingPath("$.errors[0].field").isEqualTo("code");
        code.extractingPath("$.errors[0].message").asString().contains("LUMP_SUM_LOAN");

        var size = badRequest(mvc.get().uri("/applications?size=1000").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)).exchange());
        size.extractingPath("$.errors[0].field").isEqualTo("size");
        size.extractingPath("$.errors[0].message").isEqualTo("должно быть не больше 100");
    }

    private AbstractJsonContentAssert<?> badRequest(MvcTestResult result) {
        return assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
    }

    private Object body(MvcTestResult result) {
        return jsonMapper.readValue(result.getResponse().getContentAsByteArray(), Object.class);
    }
}
