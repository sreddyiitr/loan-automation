package com.funding.api.seed;

import com.funding.persistence.ApplicationWriter;
import com.funding.seed.Fixture;
import java.util.List;

/** Generated fixture -> rows in the shape OCR writes them. */
public final class FixtureRows {
    private FixtureRows() {}

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
