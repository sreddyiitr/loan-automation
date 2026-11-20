package com.funding.api.seed;

import com.funding.domain.CheckOutcome;
import com.funding.domain.StipRequest;
import com.funding.persistence.ApplicationWriter;
import com.funding.persistence.ResultRepository;
import com.funding.persistence.StipRepository;
import com.funding.seed.Fixture;
import com.funding.seed.FixtureSnapshots;
import com.funding.seed.SeedGates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Generated fixture -> rows in the shape OCR writes them. */
public final class FixtureRows {
    private FixtureRows() {}

    /**
     * Inserts the application with every version, in the caller's transaction, and gives each earlier
     * (non-latest) version the result and stip rows its own checks produced (S5, US-9: seeded history,
     * computed by the same rule engine and income stub as the conformance gate, never typed). Returns
     * the version ids in the fixture's order.
     */
    public static List<Long> insert(JdbcClient jdbc, SeedGates gates, Fixture f) {
        List<Long> ids = new ApplicationWriter(jdbc).insert(f.loanApplicationId(), versions(f));
        ResultRepository results = new ResultRepository(jdbc);
        StipRepository stips = new StipRepository(jdbc);
        for (int i = 0; i < f.versions().size(); i++) {
            Fixture.Version v = f.versions().get(i);
            if (v.latest()) {
                continue;
            }
            long id = ids.get(i);
            Map<String, Long> docIds = new LinkedHashMap<>();
            jdbc.sql("SELECT document_type, document_id FROM documents WHERE application_version_id = ? AND is_latest_doc")
                    .param(id).query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                    .forEach(e -> docIds.put(e.getKey(), e.getValue()));
            for (CheckOutcome o : gates.outcomes(f, FixtureSnapshots.of(f, v)).values()) {
                results.record(id, o, 1, null, docIds);
                for (StipRequest stip : o.stips()) {
                    stips.createIfAbsent(id, o.check(), stip, docIds.get(StipRepository.triggeringDocumentType(o.check())));
                }
            }
        }
        return ids;
    }

    public static List<ApplicationWriter.NewVersion> versions(Fixture f) {
        return f.versions().stream().map(v -> new ApplicationWriter.NewVersion(
                v.versionNumber(), v.latest(), v.status(), v.borrowerName(), v.coBorrowerName(), v.ssnItinToken(),
                v.statedIncomeAnnual(), v.vehicleVin(), v.dealershipName(), v.dealershipId(),
                v.documents().stream().map(FixtureRows::document).toList())).toList();
    }

    public static ApplicationWriter.NewDocument document(Fixture.Document d) {
        return new ApplicationWriter.NewDocument(d.type().name(), d.documentVersion(), d.latest(), d.fileName(), d.totalPages(),
                d.sections().stream().map(s -> new ApplicationWriter.NewSection(s.name(), s.startPage(), s.endPage(),
                        s.fields().stream().map(x -> new ApplicationWriter.NewField(x.key(), x.value(), x.confidence(),
                                x.boundingBox(), x.page())).toList())).toList());
    }
}
