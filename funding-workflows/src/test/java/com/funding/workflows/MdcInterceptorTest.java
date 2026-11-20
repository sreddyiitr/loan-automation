package com.funding.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** T607, NFR-05: activity log lines carry the parent workflow and version ids, and nothing leaks to the next task on the thread. */
class MdcInterceptorTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void NFR_05_childActivitiesAreLabelledWithTheParentAndVersion() {
        Map<String, String> seen = MdcInterceptor.withIds("funding-it-2ea7a8-242-TILA-a1", () -> {
            MdcInterceptor.loanApplicationId("LA-1001");
            return MDC.getCopyOfContextMap();
        });
        assertThat(seen).containsEntry("workflowId", "funding-it-2ea7a8-242").containsEntry("applicationVersionId", "242")
                .containsEntry("loanApplicationId", "LA-1001");
        assertThat(MdcInterceptor.withIds("funding-local-7", MDC::getCopyOfContextMap))
                .containsEntry("workflowId", "funding-local-7").containsEntry("applicationVersionId", "7");
    }

    @Test
    void NFR_05_nothingIsLeftBehindEvenWhenTheActivityFails() {
        MDC.put("unrelated", "kept"); // someone else's key survives
        assertThatThrownBy(() -> MdcInterceptor.withIds("funding-local-9-INCOME-a2", () -> {
            MdcInterceptor.loanApplicationId("LA-9");
            throw new IllegalStateException("income API down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("unrelated");
    }
}
