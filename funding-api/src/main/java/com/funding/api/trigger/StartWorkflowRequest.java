package com.funding.api.trigger;

/** Body of {@code POST /api/v1/funding-workflows} (plan "Start-workflow API"). */
public record StartWorkflowRequest(String eventType, String loanApplicationId, Long applicationVersionId,
                                   Long documentId, String sourceMessageId) {}
