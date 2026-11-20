package com.funding.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

    /**
     * AS-9.1: creates version n+1 of the application as the new latest and takes the flag off the
     * previous latest, in the caller's transaction. The previous latest row is locked first, so of two
     * concurrent calls one wins and the other fails (here or on the one-latest unique index). The
     * version number and latest flag in {@code v} are ignored; its status is used as given (DRAFT for
     * a dealer resubmission, so nothing triggers until it is submitted). Refused with
     * {@link ResubmissionRefusedException} once the latest version is FUNDED or its disbursement
     * was requested or completed (C-24).
     */
    public long addVersion(String loanApplicationId, NewVersion v) {
        int next = takeLatestFlag(loanApplicationId);
        return insertVersion(loanApplicationId, new NewVersion(next, true, v.status(), v.borrowerName(), v.coBorrowerName(),
                v.ssnItinToken(), v.statedIncomeAnnual(), v.vehicleVin(), v.dealershipName(), v.dealershipId(), v.documents()));
    }

    /**
     * Like {@link #addVersion}, but the new DRAFT version copies the previous latest version's
     * application fields in SQL, so the SSN token is copied without ever being read into the app
     * (FR-13). {@code documents} become the new version's documents.
     */
    public long addVersionCopying(String loanApplicationId, List<NewDocument> documents) {
        Long previous = jdbc.sql("SELECT application_version_id FROM application_versions WHERE loan_application_id = ? AND is_latest_version")
                .param(loanApplicationId).query(Long.class).optional().orElseThrow(() -> new IllegalStateException("no latest version"));
        int next = takeLatestFlag(loanApplicationId);
        long id = jdbc.sql("""
                INSERT INTO application_versions (loan_application_id, version_number, is_latest_version, borrower_name,
                  co_borrower_name, ssn_itin_encrypted, stated_income_annual, vehicle_vin, dealership_name, dealership_id, version_status)
                SELECT loan_application_id, ?, TRUE, borrower_name, co_borrower_name, ssn_itin_encrypted, stated_income_annual,
                       vehicle_vin, dealership_name, dealership_id, 'DRAFT'
                  FROM application_versions WHERE application_version_id = ? RETURNING application_version_id""")
                .params(next, previous).query(Long.class).single();
        // The resubmission stays with the funder who has the deal (AS-10.3 reassigns explicitly).
        jdbc.sql("""
                UPDATE application_versions SET assigned_funder =
                       (SELECT assigned_funder FROM application_versions WHERE application_version_id = ?)
                 WHERE application_version_id = ?""").params(previous, id).update();
        documents.forEach(d -> insertDocument(id, d));
        return id;
    }

    /**
     * Locks the current latest version, takes its latest flag and returns the next version number.
     * The disbursement activity writes its REQUESTED row under the same row lock before it pays
     * (AS-8.1), so the check below can't miss a payment that is about to start.
     */
    private int takeLatestFlag(String loanApplicationId) {
        java.util.Map.Entry<Long, String> latest = jdbc.sql("""
                SELECT application_version_id, version_status FROM application_versions
                 WHERE loan_application_id = ? AND is_latest_version FOR UPDATE""")
                .param(loanApplicationId).query((rs, i) -> java.util.Map.entry(rs.getLong(1), rs.getString(2))).optional()
                .orElseThrow(() -> new IllegalStateException("application " + loanApplicationId
                        + " has no latest version (another new version was created concurrently?)"));
        long previous = latest.getKey();
        if ("FUNDED".equals(latest.getValue())) {
            throw new ResubmissionRefusedException("VERSION_FUNDED", loanApplicationId + " is already funded (version " + previous + ")");
        }
        String paying = jdbc.sql("SELECT status FROM disbursements WHERE application_version_id = ? AND status IN ('REQUESTED','COMPLETED')")
                .param(previous).query(String.class).optional().orElse(null);
        if (paying != null) {
            throw new ResubmissionRefusedException("DISBURSEMENT_STARTED", "version " + previous + " of " + loanApplicationId
                    + " has a " + paying + " disbursement; resolve it (retry it, C-8) before resubmitting");
        }
        int next = jdbc.sql("SELECT max(version_number) + 1 FROM application_versions WHERE loan_application_id = ?")
                .param(loanApplicationId).query(Integer.class).single();
        jdbc.sql("UPDATE application_versions SET is_latest_version = FALSE WHERE application_version_id = ?").param(previous).update();
        return next;
    }

    public record AddedDocument(long documentId, int documentVersion) {}

    /**
     * AS-9.4: adds a new latest version of one document type to an application version, in the
     * caller's transaction: the previous latest document of that type loses {@code is_latest_doc},
     * and the new one gets the next document version number. The DB refuses it for an APPROVED or
     * final version ({@code DOCUMENTS_FROZEN}, AS-9.7) and, for a version under review, writes the
     * {@code DOCUMENT_VERSION_ADDED} outbox event (AS-1.8). The version and latest flag in {@code d}
     * are ignored. Lock the version first (it must be the latest version).
     */
    public AddedDocument addDocumentVersion(long versionId, NewDocument d) {
        int next = jdbc.sql("""
                SELECT coalesce(max(document_version_number), 0) + 1 FROM documents
                 WHERE application_version_id = ? AND document_type = ?""")
                .params(versionId, d.type()).query(Integer.class).single();
        jdbc.sql("UPDATE documents SET is_latest_doc = FALSE WHERE application_version_id = ? AND document_type = ? AND is_latest_doc")
                .params(versionId, d.type()).update();
        long id = insertDocument(versionId, new NewDocument(d.type(), next, true, d.fileName(), d.totalPages(), d.sections()));
        return new AddedDocument(id, next);
    }

    /** The latest documents of a version with their sections and fields, in the shape {@link #insertDocument} takes. */
    public List<NewDocument> latestDocuments(long versionId) {
        List<Map<String, Object>> docs = jdbc.sql("""
                SELECT document_id, document_type, document_version_number, file_name, total_pages FROM documents
                 WHERE application_version_id = ? AND is_latest_doc ORDER BY document_type""")
                .param(versionId).query().listOfRows();
        List<NewDocument> out = new java.util.ArrayList<>();
        for (Map<String, Object> d : docs) {
            List<NewSection> sections = new java.util.ArrayList<>();
            for (Map<String, Object> s : jdbc.sql("""
                    SELECT section_id, section_name, start_page, end_page FROM document_sections
                     WHERE document_id = ? ORDER BY section_id""").param(d.get("document_id")).query().listOfRows()) {
                List<NewField> fields = jdbc.sql("""
                        SELECT field_key, field_value, confidence_score, bounding_box::text AS box, page_number
                          FROM extracted_fields WHERE section_id = ? ORDER BY field_id""")
                        .param(s.get("section_id"))
                        .query((rs, i) -> new NewField(rs.getString("field_key"), rs.getString("field_value"),
                                rs.getBigDecimal("confidence_score"), box(rs.getString("box")), (Integer) rs.getObject("page_number")))
                        .list();
                sections.add(new NewSection((String) s.get("section_name"), (Integer) s.get("start_page"), (Integer) s.get("end_page"), fields));
            }
            out.add(new NewDocument((String) d.get("document_type"), (Integer) d.get("document_version_number"), true,
                    (String) d.get("file_name"), (Integer) d.get("total_pages"), sections));
        }
        return out;
    }

    private static List<Integer> box(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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
