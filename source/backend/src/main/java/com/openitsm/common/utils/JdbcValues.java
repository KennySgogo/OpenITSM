package com.openitsm.common.utils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public final class JdbcValues {
    private JdbcValues() {}
    public static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    public static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
