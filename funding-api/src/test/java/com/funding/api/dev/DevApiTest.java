package com.funding.api.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funding.persistence.ApplicationWriter;
import com.funding.persistence.TestDatabase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** T505: dev add-version (AS-9.1) and add-document (AS-9.4, AS-1.8, AS-9.7) endpoints. */
class DevApiTest {

    private static final String BO_V2 = """
            {"type":"BUYERS_ORDER","fileName":"bo-v2.pdf","totalPages":2,"sections":[{"name":"Pricing","startPage":2,"endPage":2,
             "fields":[{"key":"total_vehicle_cash_price","value":"31450.00","confidence":0.97,"boundingBox":[1,2,3,4],"page":2}]}]}""";

    private JdbcClient jdbc;
    private MockMvc mvc;
    private long v1;

    @BeforeEach
    void setUp() {
        TestDatabase db = TestDatabase.fresh();
        jdbc = db.jdbc();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
        // @Transactional isn't applied in a standalone MockMvc; run each request in a transaction like the app does.
        DevController controller = new DevController(jdbc);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new TransactionPerRequest(tx))
                .setControllerAdvice(new com.funding.api.ProblemHandler())
                .build();
        v1 = new ApplicationWriter(jdbc).insert("LA-1", List.of(new ApplicationWriter.NewVersion(1, true, "DRAFT", "Jordan Rivera", null,
                "enc:t:LA-1", new BigDecimal("96000.00"), "4T1C11AK7RU100101", "Lone Star Motors", "DLR-100", List.of(
                        new ApplicationWriter.NewDocument("RISC", 1, true, "risc.pdf", 5, List.of(new ApplicationWriter.NewSection(
                                "Itemization", 2, 3, List.of(new ApplicationWriter.NewField("cash_price", "31450.00",
                                        new BigDecimal("0.97"), List.of(1, 2, 3, 4), 2))))),
                        new ApplicationWriter.NewDocument("BUYERS_ORDER", 1, true, "bo.pdf", 2, List.of(new ApplicationWriter.NewSection(
                                "Pricing", 2, 2, List.of(new ApplicationWriter.NewField("total_vehicle_cash_price", "31450.01",
                                        new BigDecimal("0.97"), List.of(1, 2, 3, 4), 2))))))))).get(0);
    }

    private void versionStatus(String s) {
        jdbc.sql("UPDATE application_versions SET version_status = ? WHERE application_version_id = ?").params(s, v1).update();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        var req = post("/api/v1/dev/applications/LA-1/" + path).contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(body == null ? req : req.content(body));
    }

    private String value(long versionId, String type, String key) {
        return jdbc.sql("""
                SELECT f.field_value FROM documents d JOIN document_sections s USING (document_id) JOIN extracted_fields f USING (section_id)
                 WHERE d.application_version_id = ? AND d.document_type = ? AND d.is_latest_doc AND f.field_key = ?""")
                .params(versionId, type, key).query(String.class).single();
    }

    @Test
    void AS_9_1_newVersionCopiesTheLatestAndTakesTheFlag() throws Exception {
        versionStatus("PENDING_REVIEW");
        versionStatus("STIPULATED");
        String body = postJson("versions", "{\"documents\":[" + BO_V2 + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.previousVersionId").value(v1))
                .andReturn().getResponse().getContentAsString();
        long v2 = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.applicationVersionId")).longValue();

        Map<String, Object> rows = jdbc.sql("""
                SELECT (SELECT is_latest_version FROM application_versions WHERE application_version_id = ?) AS v1_latest,
                       (SELECT version_status FROM application_versions WHERE application_version_id = ?) AS v1_status,
                       (SELECT is_latest_version FROM application_versions WHERE application_version_id = ?) AS v2_latest,
                       (SELECT ssn_itin_encrypted FROM application_versions WHERE application_version_id = ?) AS v2_ssn""")
                .params(v1, v1, v2, v2).query().singleRow();
        assertThat(rows).containsEntry("v1_latest", false).containsEntry("v1_status", "STIPULATED")
                .containsEntry("v2_latest", true).containsEntry("v2_ssn", "enc:t:LA-1");
        assertThat(value(v2, "RISC", "cash_price")).isEqualTo("31450.00");                     // copied
        assertThat(value(v2, "BUYERS_ORDER", "total_vehicle_cash_price")).isEqualTo("31450.00"); // replaced
        assertThat(value(v1, "BUYERS_ORDER", "total_vehicle_cash_price")).isEqualTo("31450.01"); // V1's stays as history
        // V2 is a DRAFT: neither its creation nor its copied documents trigger anything (V1's own submit event is the only one).
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE (payload->>'application_version_id')::bigint = ?")
                .param(v2).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE event_type = 'DOCUMENT_VERSION_ADDED'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void AS_1_8_addDocumentUnderReviewIsTheNewLatestAndTriggers() throws Exception {
        versionStatus("PENDING_REVIEW");
        versionStatus("STIPULATED");
        postJson("documents", BO_V2).andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("BUYERS_ORDER"))
                .andExpect(jsonPath("$.documentVersion").value(2))
                .andExpect(jsonPath("$.applicationVersionId").value(v1));
        assertThat(value(v1, "BUYERS_ORDER", "total_vehicle_cash_price")).isEqualTo("31450.00");
        assertThat(jdbc.sql("SELECT event_type FROM outbox_events ORDER BY event_id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("DOCUMENT_VERSION_ADDED");
    }

    @Test
    void AS_9_7_addDocumentToAnApprovedVersionIs409DocumentsFrozen() throws Exception {
        versionStatus("PENDING_REVIEW");
        versionStatus("APPROVED");
        postJson("documents", BO_V2).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("DOCUMENTS_FROZEN"));
        // nothing changed: the old document is still the latest
        assertThat(value(v1, "BUYERS_ORDER", "total_vehicle_cash_price")).isEqualTo("31450.01");
    }

    @Test
    void badDocumentIs400AndUnknownApplicationIs404() throws Exception {
        postJson("documents", "{\"type\":\"PAYSTUB\",\"fileName\":\"x.pdf\",\"totalPages\":1,\"sections\":[]}")
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/dev/applications/LA-404/documents").contentType(MediaType.APPLICATION_JSON).content(BO_V2))
                .andExpect(status().isNotFound());
    }
}
