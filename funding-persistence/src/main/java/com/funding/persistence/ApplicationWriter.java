package com.funding.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Inserts an application with its versions, documents, sections and fields, in the shape OCR
 * writes them (used by the seed loader and the dev endpoints). Versions are inserted as given; the
 * caller decides the status (seeded latest versions are DRAFT, so nothing triggers).
 */
public class ApplicationWriter {

    public record NewVersion(int versionNumber, boolean latest, String status, String borrowerName, String coBorrowerName,
                             String ssnItinToken, BigDecimal statedIncomeAnnual, String vehicleVin,
                             String dealershipName, String dealershipId, List<NewDocument> documents) {}

    public record NewDocument(String type, int documentVersion, boolean latest, String fileName, int totalPages,
                              List<NewSection> sections) {}

    public record NewSection(String name, int startPage, int endPage, List<NewField> fields) {}

    public record NewField(String key, String value, BigDecimal confidence, List<Integer> boundingBox, Integer page) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;

    public ApplicationWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the version ids in the order given. */
    public List<Long> insert(String loanApplicationId, List<NewVersion> versions) {
        jdbc.sql("INSERT INTO loan_applications (loan_application_id) VALUES (?)").param(loanApplicationId).update();
        return versions.stream().map(v -> insertVersion(loanApplicationId, v)).toList();
    }

    public long insertVersion(String loanApplicationId, NewVersion v) {
        long id = jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, version_number, is_latest_version, borrower_name,
                  co_borrower_name, ssn_itin_encrypted, stated_income_annual, vehicle_vin, dealership_name, dealership_id, version_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING application_version_id""")
                .params(loanApplicationId, v.versionNumber(), v.latest(), v.borrowerName(), v.coBorrowerName(),
                        v.ssnItinToken(), v.statedIncomeAnnual(), v.vehicleVin(), v.dealershipName(), v.dealershipId(), v.status())
                .query(Long.class).single();
        v.documents().forEach(d -> insertDocument(id, d));
        return id;
    }

    public long insertDocument(long versionId, NewDocument d) {
        long docId = jdbc.sql("""
                INSERT INTO documents (application_version_id, document_type, document_version_number, is_latest_doc, file_name, total_pages)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING document_id""")
                .params(versionId, d.type(), d.documentVersion(), d.latest(), d.fileName(), d.totalPages())
                .query(Long.class).single();
        for (NewSection s : d.sections()) {
            long sectionId = jdbc.sql("INSERT INTO document_sections (document_id, section_name, start_page, end_page) VALUES (?, ?, ?, ?) RETURNING section_id")
                    .params(docId, s.name(), s.startPage(), s.endPage()).query(Long.class).single();
            for (NewField f : s.fields()) {
                jdbc.sql("INSERT INTO extracted_fields (section_id, field_key, field_value, confidence_score, bounding_box, page_number) VALUES (?, ?, ?, ?, ?::jsonb, ?)")
                        .params(sectionId, f.key(), f.value(), f.confidence(), json(f.boundingBox()), f.page()).update();
            }
        }
        return docId;
    }

    private static String json(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
