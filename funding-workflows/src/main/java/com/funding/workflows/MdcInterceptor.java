package com.funding.workflows;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * T607, NFR-05: every log line an activity writes carries the parent {@code workflowId} and the
 * {@code applicationVersionId} (and {@code loanApplicationId} once the activity has read the version),
 * and nothing is left behind afterwards. Activity threads are reused, so a value an activity put in
 * the MDC without removing it would label the next, unrelated log lines on that thread (which is
 * what happened before this interceptor). The ids come from the workflow ID, so no activity has to
 * remember to set or clear them.
 */
public class MdcInterceptor extends WorkerInterceptorBase {

    public static final String WORKFLOW_ID = "workflowId";
    public static final String VERSION_ID = "applicationVersionId";
    public static final String LOAN_ID = "loanApplicationId";

    /** {@code funding-<env>-<versionId>}, optionally followed by a child's {@code -<CHECK>-a<attempt>}. */
    private static final Pattern PARENT = Pattern.compile("^(funding-.+-(\\d+))(?:-[A-Z]+-a\\d+)?$");

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
        return new ActivityInboundCallsInterceptorBase(next) {
            private String workflowId;

            @Override
            public void init(ActivityExecutionContext context) {
                // Activity.getExecutionContext() isn't available at this level; the SDK hands the context in here.
                workflowId = context.getInfo().getWorkflowId();
                super.init(context);
            }

            @Override
            public ActivityOutput execute(ActivityInput input) {
                return withIds(workflowId, () -> super.execute(input));
            }
        };
    }

    /** Runs {@code body} with the correlation ids of {@code workflowId} in the MDC, and always removes them after. */
    public static <T> T withIds(String workflowId, Supplier<T> body) {
        Matcher m = PARENT.matcher(workflowId == null ? "" : workflowId);
        try {
            if (m.matches()) {
                MDC.put(WORKFLOW_ID, m.group(1));
                MDC.put(VERSION_ID, m.group(2));
            } else if (workflowId != null) {
                MDC.put(WORKFLOW_ID, workflowId);
            }
            return body.get();
        } finally {
            MDC.remove(WORKFLOW_ID);
            MDC.remove(VERSION_ID);
            MDC.remove(LOAN_ID);
        }
    }

    /** For an activity that has read the version: adds the loan id for the rest of its execution. */
    public static void loanApplicationId(String loanApplicationId) {
        if (loanApplicationId != null) {
            MDC.put(LOAN_ID, loanApplicationId);
        }
    }
}
