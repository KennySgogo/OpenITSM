package com.openitsm;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class TicketTypeTestSupport {
    private TicketTypeTestSupport() {}
    static UUID type(JdbcTemplate db, String name) {
        UUID id = UUID.nameUUIDFromBytes(("test-ticket-type:" + name).getBytes(StandardCharsets.UTF_8));
        db.update("INSERT INTO ticket_type(id,name) VALUES (?,?) ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name,active=true", id, name);
        return id;
    }
}
