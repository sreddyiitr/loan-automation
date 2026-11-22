package com.funding.api.funder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T409 / FR-17: the funder API's OpenAPI document is checked in as
 * {@code contracts/funder-api.openapi.yaml} for spec 002 (the dashboard). This fails when the
 * controller and the checked-in contract drift; regenerate with {@code -Dwrite.contract=true}.
 */
@org.springframework.boot.autoconfigure.ImportAutoConfiguration({
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.core.properties.SpringDocConfigProperties.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class})
@org.springframework.context.annotation.Import(com.funding.api.auth.SecurityConfig.class)
@WebMvcTest(controllers = {FunderController.class, TeamController.class}, properties = {
        "springdoc.paths-to-match=/api/v1/applications/**,/api/v1/versions/**,/api/v1/me,/api/v1/users,/api/v1/stip-codes",
        "springdoc.writer-with-order-by-keys=true"})
class FunderOpenApiTest {

    private static final Path CONTRACT = Path.of("../contracts/funder-api.openapi.yaml");

    @Autowired private MockMvc mvc;
    @MockBean private FunderService service;
    @MockBean private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void FR_17_funderContractIsCheckedInAndCurrent() throws Exception {
        String yaml = mvc.perform(get("/v3/api-docs.yaml")).andReturn().getResponse().getContentAsString()
                .replaceAll("(?m)^(\\s*- url: ).*$", "$1http://localhost:8080"); // MockMvc's server URL
        assertThat(yaml).startsWith("openapi:");
        if (Boolean.getBoolean("write.contract")) {
            Files.writeString(CONTRACT, yaml);
        }
        assertThat(yaml).contains("/api/v1/stip-codes", "/api/v1/versions/{versionId}/approve", "/api/v1/versions/{versionId}/stipulations/{stipId}",
                "bearerAuth").doesNotContain("X-Funder-Id");
        assertThat(Files.exists(CONTRACT)).as("run once with -Dwrite.contract=true to create " + CONTRACT).isTrue();
        assertThat(yaml).as("contracts/funder-api.openapi.yaml is stale: rerun with -Dwrite.contract=true and commit it")
                .isEqualTo(Files.readString(CONTRACT));
    }
}
