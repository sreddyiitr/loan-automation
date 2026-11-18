package com.funding.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** FR-10 / P-4: every status, stip and assignment change records its actor. */
public class AuditRepository {

    public record Entry(String entityType, String entityId, String action, String actor, String before, String after) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String entityType, String entityId, String action, String actor, Object before, Object after) {
        jdbc.sql("INSERT INTO audit_log (entity_type, entity_id, action, actor, before_state, after_state) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)")
                .params(entityType, entityId, action, actor, json(before), json(after)).update();
    }

    public List<Entry> forEntity(String entityType, String entityId) {
        return jdbc.sql("SELECT entity_type, entity_id, action, actor, before_state::text, after_state::text FROM audit_log WHERE entity_type = ? AND entity_id = ? ORDER BY audit_id")
                .params(entityType, entityId)
                .query((rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    private static String json(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(o instanceof String s ? Map.of("value", s) : o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
