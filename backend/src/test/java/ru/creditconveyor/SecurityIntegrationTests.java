package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import ru.creditconveyor.api.ApplicationsApi;
import ru.creditconveyor.api.BackofficeApi;

class SecurityIntegrationTests extends IntegrationTest {

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    BackofficeApi backofficeController;

    @Autowired
    ApplicationsApi applicationsController;

    @Test
    void demoLoginAsClientReturnsMainDemoClient() {
        var body = assertThat(login("CLIENT")).hasStatusOk().bodyJson();
        body.extractingPath("$.accessToken").asString().isNotBlank();
        body.extractingPath("$.expiresIn").isEqualTo(86400);
        body.extractingPath("$.user.fullName").isEqualTo("Иван Петров");
        body.extractingPath("$.user.role").isEqualTo("CLIENT");
        body.extractingPath("$.user.companies[*].id").asArray()
                .containsExactly(SEVERNY_VETER, MOROZOV, ALTAIR, TECHNOPROM, SOKOLOVA, VEKTOR_STROY);
    }

    @Test
    void demoLoginTokenWorksForApi() {
        String token = JsonPath.read(content(login("UNDERWRITER")), "$.accessToken");

        var me = assertThat(mvc.get().uri("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk().bodyJson();
        me.extractingPath("$.fullName").isEqualTo("Мария Смирнова");
        me.extractingPath("$.role").isEqualTo("UNDERWRITER");
        me.doesNotHavePath("$.companies");
    }

    @Test
    void demoLoginValidatesRole() {
        assertThat(mvc.post().uri("/auth/demo-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"ADMIN\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void requiresTokenEverywhereExceptPublicEndpoints() {
        for (String uri : List.of("/products", "/dictionaries", "/me", "/companies", "/applications", "/notifications")) {
            var body = assertThat(mvc.get().uri(uri))
                    .as(uri)
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .bodyJson();
            body.extractingPath("$.title").isEqualTo("Требуется вход");
        }
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
        assertThat(mvc.get().uri("/openapi.yaml")).hasStatusOk();
    }

    /** Swagger UI показывает контракт; сгенерированная из кода спецификация и /actuator/info закрыты. */
    @Test
    void swaggerUiShowsContractAndGeneratedSpecIsClosed() {
        assertThat(mvc.get().uri("/v3/api-docs/swagger-config")).hasStatusOk()
                .bodyJson().extractingPath("$.url").isEqualTo("/api/v1/openapi.yaml");
        for (String uri : List.of("/v3/api-docs", "/v3/api-docs.yaml", "/actuator/info")) {
            assertThat(mvc.get().uri(uri)).as(uri).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(mvc.get().uri("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/actuator/info").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsTamperedForeignAndExpiredTokens() {
        String valid = bearer(CLIENT);
        String tampered = valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA");

        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        String foreign = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims(TestcontainersConfiguration.TODAY.plusSeconds(3600))))
                .getTokenValue();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims(TestcontainersConfiguration.TODAY.minusSeconds(3600))))
                .getTokenValue();

        for (String token : List.of(tampered, "Bearer " + foreign, "Bearer " + expired, "Bearer not-a-jwt")) {
            assertThat(mvc.get().uri("/me").header(HttpHeaders.AUTHORIZATION, token))
                    .hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void backofficeIsForUnderwriterOnly() {
        assertThat(mvc.get().uri("/backoffice/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.title").isEqualTo("Нет доступа");
        assertThat(mvc.post().uri("/backoffice/applications/3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b/decision")
                .header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\": \"APPROVE\"}"))
                .hasStatus(HttpStatus.FORBIDDEN);

        assertThat(mvc.get().uri("/backoffice/applications").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatusOk();
    }

    /** Сам {@code @PreAuthorize} — в обход HTTP и правил по URL: вызов бина контроллера не той ролью. */
    @Test
    @WithMockUser(roles = "CLIENT")
    void methodSecurityGuardsBackofficeController() {
        assertThatThrownBy(() -> backofficeController.listBackofficeApplications("REVIEW", null, 0, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "UNDERWRITER")
    void methodSecurityGuardsApplicationsController() {
        assertThatThrownBy(() -> applicationsController.listApplications(null, 0, 20))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> applicationsController.acceptOffer(java.util.UUID.randomUUID()))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void clientApplicationsAreForClientsOnly() {
        assertThat(mvc.get().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .hasStatus(HttpStatus.FORBIDDEN);
        // и ещё не реализованные действия клиента тоже
        assertThat(mvc.post().uri("/applications/9b2c1d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e/offer/accept")
                .header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void clientCannotTouchAnotherCompanysApplication() {
        MvcTestResult created = mvc.post().uri("/applications").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\": \"%s\", \"purpose\": \"CASH_GAP\"}".formatted(SEVERNY_VETER))
                .exchange();
        String id = JsonPath.read(content(created), "$.id");
        String stranger = bearer(OTHER_CLIENT);

        assertForbidden(mvc.get().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.get().uri("/applications/" + id + "/history").header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.patch().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, stranger)
                .contentType(MediaType.APPLICATION_JSON).content("{\"subscription\": true}").exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/submit").header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/offer/accept").header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/offer/decline").header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/info-requests/" + id + "/response")
                .header(HttpHeaders.AUTHORIZATION, stranger).contentType(MediaType.APPLICATION_JSON).content("{}").exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/signing/request-code")
                .header(HttpHeaders.AUTHORIZATION, stranger).exchange());
        assertForbidden(mvc.post().uri("/applications/" + id + "/signing/confirm").header(HttpHeaders.AUTHORIZATION, stranger)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"1234\"}").exchange());

        // Заявка не изменилась
        assertThat(mvc.get().uri("/applications/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
    }

    @Test
    void unknownApplicationIsNotFound() {
        assertThat(mvc.get().uri("/applications/00000000-0000-0000-0000-000000000000")
                .header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.title").isEqualTo("Не найдено");
    }

    /** EventSource не умеет заголовки: токен параметром принимается только у потока уведомлений. */
    @Test
    void tokenInQueryIsAcceptedOnlyForNotificationStream() {
        String token = bearer(CLIENT).substring("Bearer ".length());

        // Поток с токеном в параметре открывается — NotificationIntegrationTests.streamDeliversNotification
        assertThat(mvc.get().uri("/notifications/stream")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/products?access_token=" + token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private void assertForbidden(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.detail").isEqualTo("Нет доступа к данным этой компании");
    }

    private MvcTestResult login(String role) {
        return mvc.post().uri("/auth/demo-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"" + role + "\"}").exchange();
    }

    private static JwtClaimsSet claims(Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer("credit-conveyor")
                .subject("7d1f3c2e-1b2a-4c5d-8e9f-0a1b2c3d4e5f")
                .issuedAt(expiresAt.minusSeconds(7200))
                .expiresAt(expiresAt)
                .claim("name", "Иван Петров")
                .claim("roles", List.of("CLIENT"))
                .build();
    }

    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
