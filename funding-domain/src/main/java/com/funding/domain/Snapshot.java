package com.funding.domain;

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
        List<GapForm> gapForms) {

    public Snapshot {
        documents = Map.copyOf(documents);
        // Null in histories recorded before slice S2 (no registry yet).
        gapForms = gapForms == null ? List.of() : List.copyOf(gapForms);
    }

    public Optional<DocumentSnapshot> document(DocumentType type) {
        return Optional.ofNullable(documents.get(type));
    }
}
