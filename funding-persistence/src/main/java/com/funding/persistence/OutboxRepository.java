package com.funding.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Transactional outbox (FR-02). Claim and mark inside one transaction; SKIP LOCKED lets relays run concurrently. */
public class OutboxRepository {

    public record Event(long eventId, String eventType, String payload) {}

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Event> claimUnpublished(int limit) {
        return jdbc.sql("SELECT event_id, event_type, payload::text FROM outbox_events WHERE published_at IS NULL ORDER BY event_id LIMIT ? FOR UPDATE SKIP LOCKED")
                .param(limit).query((rs, i) -> new Event(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public void markPublished(long eventId) {
        jdbc.sql("UPDATE outbox_events SET published_at = CURRENT_TIMESTAMP WHERE event_id = ?").param(eventId).update();
    }

    public int purgePublishedOlderThanDays(int days) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at < CURRENT_TIMESTAMP - make_interval(days => ?)").param(days).update();
    }
}
