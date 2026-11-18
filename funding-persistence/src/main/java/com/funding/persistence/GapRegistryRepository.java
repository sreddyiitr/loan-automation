package com.funding.persistence;

import com.funding.domain.GapForm;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes the GAP form registry (US-6). Locally it is seed-owned: replaced from test/data/gap-form-registry.yaml. */
public class GapRegistryRepository {
    private final JdbcClient jdbc;

    public GapRegistryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Replaces the whole registry; call inside a transaction. */
    public void replaceAll(List<GapForm> forms) {
        jdbc.sql("DELETE FROM gap_form_registry").update();
        for (GapForm f : forms) {
            jdbc.sql("INSERT INTO gap_form_registry (state_code, form_number, effective_from, effective_to) VALUES (?, ?, ?, ?)")
                    .params(f.stateCode(), f.formNumber(), f.effectiveFrom(), f.effectiveTo()).update();
        }
    }
}
