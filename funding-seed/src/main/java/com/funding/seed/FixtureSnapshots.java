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
        Fixture.Version v = f.latest();
        Map<DocumentType, DocumentSnapshot> docs = new EnumMap<>(DocumentType.class);
        long syntheticId = 1;
        for (Fixture.Document d : v.latestDocuments()) {
            List<ExtractedField> rows = d.sections().stream().flatMap(s -> s.fields().stream())
                    .map(x -> new ExtractedField(x.key(), x.value(), x.confidence())).toList();
            docs.put(d.type(), DocumentSnapshot.resolve(d.type(), syntheticId++, rows));
        }
        return new Snapshot(0L, f.loanApplicationId(), v.versionNumber(), v.vehicleVin(),
                v.statedIncomeAnnual(), v.ssnItinToken() != null, v.dealershipId(), docs, f.gapForms());
    }
}
