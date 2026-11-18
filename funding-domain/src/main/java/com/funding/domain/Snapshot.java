package com.funding.domain;

import com.funding.domain.income.IncomeVerification;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a pure rule may read about one application version. Never carries the SSN/ITIN
 * token itself (FR-13), only whether one is present. The GAP form registry travels with it so
 * the GAP rule stays I/O-free (T206).
 */
public record Snapshot(
        long applicationVersionId,
        String loanApplicationId,
        int versionNumber,
        String vehicleVin,
        BigDecimal statedIncomeAnnual,
        boolean ssnTokenPresent,
        String dealershipId,
        Map<DocumentType, DocumentSnapshot> documents,
        List<GapForm> gapForms,
        IncomeVerification incomeVerification) {

    public Snapshot {
        documents = Map.copyOf(documents);
        // Null in histories recorded before slice S2 (no registry yet).
        gapForms = gapForms == null ? List.of() : List.copyOf(gapForms);
    }

    /** Before slice S3 there was no income verification; existing callers keep working. */
    public Snapshot(long applicationVersionId, String loanApplicationId, int versionNumber, String vehicleVin,
                    BigDecimal statedIncomeAnnual, boolean ssnTokenPresent, String dealershipId,
                    Map<DocumentType, DocumentSnapshot> documents, List<GapForm> gapForms) {
        this(applicationVersionId, loanApplicationId, versionNumber, vehicleVin, statedIncomeAnnual, ssnTokenPresent,
                dealershipId, documents, gapForms, null);
    }

    /** The same snapshot with the income API's answer, for the INCOME check (US-5). */
    public Snapshot withIncome(IncomeVerification v) {
        return new Snapshot(applicationVersionId, loanApplicationId, versionNumber, vehicleVin, statedIncomeAnnual,
                ssnTokenPresent, dealershipId, documents, gapForms, v);
    }

    public Optional<DocumentSnapshot> document(DocumentType type) {
        return Optional.ofNullable(documents.get(type));
    }
}
