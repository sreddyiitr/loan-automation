package com.funding.workflows;

import com.funding.domain.Check;

/** {@code funding-<env>-<versionId>}; children {@code <parentId>-<CHECK>-a<attempt>} (FR-03). */
public final class WorkflowIds {
    private WorkflowIds() {}

    public static String parent(String env, long versionId) {
        return "funding-" + env + "-" + versionId;
    }

    public static String child(String parentId, Check check, int attempt) {
        return parentId + "-" + check.name() + "-a" + attempt;
    }

    public static String systemActor(String workflowId) {
        return "system:" + workflowId;
    }
}
