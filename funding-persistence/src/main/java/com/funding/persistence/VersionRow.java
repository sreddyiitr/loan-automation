package com.funding.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record VersionRow(
        long applicationVersionId,
        String loanApplicationId,
        int versionNumber,
        boolean latest,
        String status,
        OffsetDateTime submittedAt,
        String borrowerName,
        String coBorrowerName,
        BigDecimal statedIncomeAnnual,
        String vehicleVin,
        String dealershipName,
        String dealershipId,
        String assignedFunder) {}
