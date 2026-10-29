package ru.creditconveyor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class CompaniesIntegrationTests extends IntegrationTest {

    @Test
    void meListsCompaniesWithDemoScenariosInOrder() {
        var me = assertThat(mvc.get().uri("/me").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))).hasStatusOk().bodyJson();
        me.extractingPath("$.fullName").isEqualTo("Иван Петров");
        me.extractingPath("$.role").isEqualTo("CLIENT");
        me.extractingPath("$.companies[*].demoScenario").asArray().containsExactly(
                "Одобрение", "Ручная проверка", "Отказ: просрочка", "Предварительное одобрение",
                "Гамма-Старт", "Отказ: ликвидация");
        me.extractingPath("$.companies[1]").isEqualTo(java.util.Map.of(
                "id", MOROZOV,
                "name", "ИП Морозов Дмитрий Олегович",
                "inn", "526218493785",
                "businessType", "IP",
                "demoScenario", "Ручная проверка"));
    }

    @Test
    void companyWithoutDemoScenarioHasNoSuchField() {
        assertThat(mvc.get().uri("/me").header(HttpHeaders.AUTHORIZATION, bearer(OTHER_CLIENT))).hasStatusOk()
                .bodyJson().doesNotHavePath("$.companies[0].demoScenario");
    }

    @Test
    void listsAllCompaniesOfClient() {
        var body = assertThat(mvc.get().uri("/companies").header(HttpHeaders.AUTHORIZATION, bearer(CLIENT))).hasStatusOk().bodyJson();
        body.extractingPath("$[*].id").asArray()
                .containsExactly(SEVERNY_VETER, MOROZOV, ALTAIR, TECHNOPROM, SOKOLOVA, VEKTOR_STROY);
        body.extractingPath("$[*].businessAgeMonths").asArray().containsExactly(65, 49, 83, 138, 3, 103);
        body.extractingPath("$[5].status").isEqualTo("LIQUIDATING");
    }

    @Test
    void returnsOwnCompanyById() {
        assertThat(mvc.get().uri("/companies/" + SEVERNY_VETER).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                .hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"id": "3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90", "inn": "7841256395", "ogrn": "1217800123452",
                 "businessType": "LLC", "name": "ООО «Северный ветер»", "registrationDate": "2021-05-14",
                 "businessAgeMonths": 65, "okved": "47.11", "region": "Санкт-Петербург", "status": "ACTIVE"}
                """);
    }

    @Test
    void foreignOrUnknownCompanyIsForbidden() {
        for (String id : new String[] {FOREIGN_COMPANY, "00000000-0000-0000-0000-000000000000"}) {
            var problem = assertThat(mvc.get().uri("/companies/" + id).header(HttpHeaders.AUTHORIZATION, bearer(CLIENT)))
                    .hasStatus(HttpStatus.FORBIDDEN)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson();
            problem.extractingPath("$.detail").isEqualTo("Нет доступа к данным этой компании");
        }
        assertThat(mvc.get().uri("/companies/" + SEVERNY_VETER).header(HttpHeaders.AUTHORIZATION, bearer(OTHER_CLIENT)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void underwriterHasNoClientCompanies() {
        var me = assertThat(mvc.get().uri("/me").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER))).hasStatusOk().bodyJson();
        me.extractingPath("$.role").isEqualTo("UNDERWRITER");
        me.doesNotHavePath("$.companies");

        var body = assertThat(mvc.get().uri("/companies").header(HttpHeaders.AUTHORIZATION, bearer(UNDERWRITER)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson();
        body.extractingPath("$.title").isEqualTo("Нет доступа");
        body.extractingPath("$.detail").isEqualTo("Раздел доступен только клиенту банка");
    }
}
