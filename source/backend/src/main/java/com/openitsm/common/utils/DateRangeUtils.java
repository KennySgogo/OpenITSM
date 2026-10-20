package com.openitsm.common.utils;

import com.openitsm.common.exception.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DateRangeUtils {
    private DateRangeUtils() {}
    public static Instant[] parse(String from, String to) {
        try {
            if ((from != null && !from.matches("\\d{4}-\\d{2}-\\d{2}")) || (to != null && !to.matches("\\d{4}-\\d{2}-\\d{2}"))) throw new IllegalArgumentException();
            LocalDate start = from == null ? null : LocalDate.parse(from);
            LocalDate end = to == null ? null : LocalDate.parse(to);
            if (start != null && end != null && start.isAfter(end)) throw new IllegalArgumentException();
            ZoneId zone = ZoneId.of("Asia/Shanghai");
            return new Instant[] { start == null ? null : start.atStartOfDay(zone).toInstant(),
                end == null ? null : end.plusDays(1).atStartOfDay(zone).toInstant() };
        } catch (java.time.DateTimeException | IllegalArgumentException ex) {
            throw new BusinessException(400, "INVALID_QUERY", "创建日期范围无效");
        }
    }
}
