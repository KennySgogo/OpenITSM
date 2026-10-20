package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.DateRangeUtils;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.DashboardService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardServiceImpl implements DashboardService {
    private final TicketMapper tickets;
    private final Clock clock;
    public DashboardServiceImpl(TicketMapper tickets, Clock clock) { this.tickets = tickets; this.clock = clock; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> metrics(AuthContext context, String createdFrom, String createdTo) {
        if (!"ADMIN".equals(context.user().role)) throw new BusinessException(403, "FORBIDDEN", "仅管理员可查看统计");
        var range = DateRangeUtils.parse(createdFrom, createdTo);
        var result = new LinkedHashMap<String, Object>();
        result.put("asOf", clock.instant());
        var counts = new LinkedHashMap<String, Long>();
        for (String state : new String[] {"NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED", "CLOSED", "REJECTED"}) counts.put(state, 0L);
        for (var row : tickets.statusCounts(range[0], range[1])) counts.put((String) row.get("status"), ((Number) row.get("count")).longValue());
        result.put("totalCount", counts.values().stream().mapToLong(Long::longValue).sum());
        result.put("countsByStatus", counts);
        result.put("countsByBusinessType", tickets.typeCounts(range[0], range[1]));
        result.put("assigneeLoads", tickets.assigneeLoads(range[0], range[1]));
        var summary = tickets.resolutionMetrics(range[0], range[1]);
        result.put("unassignedCount", summary.get("unassignedCount"));
        result.put("averageResolutionSeconds", summary.get("averageResolutionSeconds"));
        result.put("resolutionSampleCount", summary.get("resolutionSampleCount"));
        return result;
    }
}
