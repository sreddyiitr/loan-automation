package com.funding.persistence;

import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.ExtractedField;
import com.funding.domain.GapForm;
import com.funding.domain.Snapshot;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds the rule engine's {@link Snapshot} from the DB: latest documents only (AS-9.4), fields
 * resolved per §8. The SSN/ITIN token is reduced to "present or not" (FR-13).
 */
public class SnapshotRepository {

    private final JdbcClient jdbc;
    private final VersionRepository versions;

    public SnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.versions = new VersionRepository(jdbc);
    }

    public record Loaded(Snapshot snapshot, VersionRow version) {}

    public Optional<Loaded> load(long versionId) {
        Optional<VersionRow> v = versions.find(versionId);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        boolean ssnPresent = jdbc.sql("SELECT ssn_itin_encrypted IS NOT NULL AND ssn_itin_encrypted <> '' FROM application_versions WHERE application_version_id = ?")
                .param(versionId).query(Boolean.class).single();

        record Row(long documentId, String type, String key, String value, java.math.BigDecimal confidence) {}
        List<Row> rows = jdbc.sql("""
                SELECT d.document_id, d.document_type, f.field_key, f.field_value, f.confidence_score
                  FROM documents d
                  JOIN document_sections s ON s.document_id = d.document_id
                  JOIN extracted_fields f ON f.section_id = s.section_id
                 WHERE d.application_version_id = ? AND d.is_latest_doc
                 ORDER BY d.document_id, s.section_id, f.field_id""")
                .param(versionId)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5)))
                .list();
        Map<Long, List<ExtractedField>> byDoc = new LinkedHashMap<>();
        Map<Long, DocumentType> types = new LinkedHashMap<>();
        for (Row r : rows) {
            byDoc.computeIfAbsent(r.documentId(), k -> new ArrayList<>()).add(new ExtractedField(r.key(), r.value(), r.confidence()));
            types.put(r.documentId(), DocumentType.valueOf(r.type()));
        }
        // Latest documents with no fields still count as present.
        jdbc.sql("SELECT document_id, document_type FROM documents WHERE application_version_id = ? AND is_latest_doc")
                .param(versionId).query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list()
                .forEach(e -> {
                    types.putIfAbsent(e.getKey(), DocumentType.valueOf(e.getValue()));
                    byDoc.putIfAbsent(e.getKey(), List.of());
                });
        Map<DocumentType, DocumentSnapshot> docs = new EnumMap<>(DocumentType.class);
        byDoc.forEach((id, fields) -> docs.put(types.get(id), DocumentSnapshot.resolve(types.get(id), id, fields)));

        VersionRow row = v.get();
        return Optional.of(new Loaded(new Snapshot(row.applicationVersionId(), row.loanApplicationId(), row.versionNumber(),
                row.vehicleVin(), row.statedIncomeAnnual(), ssnPresent, row.dealershipId(), docs, gapForms()), row));
    }

    /** The GAP form registry (T206): passed to the pure GAP rule with the snapshot. */
    public List<GapForm> gapForms() {
        return jdbc.sql("SELECT state_code, form_number, effective_from, effective_to FROM gap_form_registry ORDER BY state_code, form_number, effective_from")
                .query((rs, i) -> new GapForm(rs.getString(1).trim(), rs.getString(2), rs.getObject(3, java.time.LocalDate.class),
                        rs.getObject(4, java.time.LocalDate.class)))
                .list();
    }
}
