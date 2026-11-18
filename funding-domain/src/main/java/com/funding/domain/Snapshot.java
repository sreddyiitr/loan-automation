package com.funding.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a pure rule may read about one application version. Never carries the SSN/ITIN
 * token itself (FR-13), only whether one is present.
 */
public record Snapshot(
        long applicationVersionId,
        String loanApplicationId,
        int versionNumber,
        String vehicleVin,
        BigDecimal statedIncomeAnnual,
        boolean ssnTokenPresent,
        String dealershipId,
        Map<DocumentType, DocumentSnapshot> documents) {

    public Snapshot {
        documents = Map.copyOf(documents);
    }

    public Optional<DocumentSnapshot> document(DocumentType type) {
        return Optional.ofNullable(documents.get(type));
    }
}
