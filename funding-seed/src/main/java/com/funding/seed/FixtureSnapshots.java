package com.funding.seed;

import com.funding.domain.DocumentSnapshot;
import com.funding.domain.DocumentType;
import com.funding.domain.ExtractedField;
import com.funding.domain.Snapshot;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds the rule engine's input from a fixture exactly as the app builds it from the DB (latest docs only). */
public final class FixtureSnapshots {
    private FixtureSnapshots() {}

    public static Snapshot of(Fixture f) {
        return of(f, f.latest(), f.latest().latestDocuments());
    }

    /** S5: an earlier application version, as its own checks saw it. */
    public static Snapshot of(Fixture f, Fixture.Version v) {
        return of(f, v, v.latestDocuments());
    }

    /** S5: the latest version once the intent's amendments have replaced the documents of their type (AS-1.8). */
    public static Snapshot amended(Fixture f) {
        List<Fixture.Document> docs = new java.util.ArrayList<>(f.latest().latestDocuments());
        for (Fixture.Document a : f.amendments()) {
            docs.removeIf(d -> d.type() == a.type());
            docs.add(a);
        }
        return of(f, f.latest(), docs);
    }

    private static Snapshot of(Fixture f, Fixture.Version v, List<Fixture.Document> latestDocs) {
        Map<DocumentType, DocumentSnapshot> docs = new EnumMap<>(DocumentType.class);
        long syntheticId = 1;
        for (Fixture.Document d : latestDocs) {
            List<ExtractedField> rows = d.sections().stream().flatMap(s -> s.fields().stream())
                    .map(x -> new ExtractedField(x.key(), x.value(), x.confidence())).toList();
            docs.put(d.type(), DocumentSnapshot.resolve(d.type(), syntheticId++, rows));
        }
        return new Snapshot(0L, f.loanApplicationId(), v.versionNumber(), v.vehicleVin(),
                v.statedIncomeAnnual(), v.ssnItinToken() != null, v.dealershipId(), docs, f.gapForms());
    }
}
