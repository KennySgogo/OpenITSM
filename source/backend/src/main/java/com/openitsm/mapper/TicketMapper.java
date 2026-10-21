package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openitsm.entity.TicketEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** M02 persistence and the existing M01 membership/visibility queries. */
@Mapper
public interface TicketMapper extends BaseMapper<TicketEntity> {
    @Select("SELECT bt.id,bt.name,bt.active FROM ticket_type bt WHERE bt.active OR EXISTS "
        + "(SELECT 1 FROM ticket t WHERE t.business_type_id=bt.id AND (CAST(#{actor} AS uuid) IS NULL OR t.requester_id=#{actor})) ORDER BY bt.name,bt.id")
    java.util.List<java.util.Map<String, Object>> filterTypes(@Param("actor") UUID employee);

    @Select("SELECT u.id,u.name FROM app_user u WHERE EXISTS (SELECT 1 FROM ticket t WHERE t.requester_id=u.id) ORDER BY u.name,u.id")
    java.util.List<java.util.Map<String, Object>> filterRequesters();

    @Select("SELECT u.id,u.name FROM app_user u WHERE EXISTS (SELECT 1 FROM ticket t WHERE t.assignee_id=u.id) ORDER BY u.name,u.id")
    java.util.List<java.util.Map<String, Object>> filterAssignees();
    String DATE_RANGE = " WHERE (CAST(#{from} AS timestamptz) IS NULL OR t.created_at &gt;= #{from}) AND (CAST(#{to} AS timestamptz) IS NULL OR t.created_at &lt; #{to}) ";

    @Select("<script>SELECT status, count(*) AS count FROM ticket t" + DATE_RANGE + " GROUP BY status</script>")
    java.util.List<java.util.Map<String, Object>> statusCounts(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    @Select("<script>SELECT bt.id, bt.name, count(*) AS count FROM ticket t JOIN ticket_type bt ON bt.id=t.business_type_id"
        + DATE_RANGE + " GROUP BY bt.id, bt.name ORDER BY bt.name, bt.id</script>")
    java.util.List<java.util.Map<String, Object>> typeCounts(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    @Select("<script>SELECT u.id, u.name, count(*) AS &quot;openCount&quot; FROM ticket t JOIN app_user u ON u.id=t.assignee_id"
        + DATE_RANGE + " AND t.status IN ('NEW','IN_PROGRESS','ON_HOLD','RESOLVED') GROUP BY u.id,u.name ORDER BY u.name,u.id</script>")
    java.util.List<java.util.Map<String, Object>> assigneeLoads(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    @Select("<script>SELECT count(*) FILTER (WHERE assignee_id IS NULL AND status IN ('NEW','IN_PROGRESS','ON_HOLD','RESOLVED')) AS &quot;unassignedCount&quot;, "
        + "count(*) FILTER (WHERE status IN ('RESOLVED','CLOSED') AND resolved_at &gt;= created_at) AS &quot;resolutionSampleCount&quot;, "
        + "avg(extract(epoch FROM resolved_at-created_at)) FILTER (WHERE status IN ('RESOLVED','CLOSED') AND resolved_at &gt;= created_at) AS &quot;averageResolutionSeconds&quot; "
        + "FROM ticket t" + DATE_RANGE + "</script>")
    java.util.Map<String, Object> resolutionMetrics(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
    default Page<TicketEntity> findPage(UUID actor, String view, int page, String status, String businessType, String q) {
        return findPage(actor, view, page, status, businessType, q, null, "ASSIGNED".equals(view) ? "OPEN" : "ALL", null, null, null, null, null);
    }

    default Page<TicketEntity> findPage(UUID actor, String view, int page, String status, String businessType, String q,
        UUID type, String lifecycle, java.time.Instant from, java.time.Instant to, String priority, UUID requester, UUID assignee) {
        var query = new QueryWrapper<TicketEntity>();
        switch (view) {
            case "MINE" -> query.eq("requester_id", actor);
            case "ASSIGNED" -> query.eq("assignee_id", actor);
            case "UNASSIGNED" -> query.isNull("assignee_id");
            default -> { /* ALL; validated by the service. */ }
        }
        if (type != null) query.eq("business_type_id", type);
        if (lifecycle.equals("OPEN")) query.in("status", "NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED");
        if (lifecycle.equals("ENDED")) query.in("status", "CLOSED", "REJECTED");
        if (from != null) query.ge("created_at", from);
        if (to != null) query.lt("created_at", to);
        if (priority != null) query.eq("priority", priority);
        if (requester != null) query.eq("requester_id", requester);
        if (assignee != null) query.eq("assignee_id", assignee);
        if (status != null) query.eq("status", status);
        if (businessType != null) query.eq("business_type", businessType);
        if (q != null) {
            String term = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            query.and(it -> it.apply("number ILIKE {0}", term).or().apply("title ILIKE {0}", term)
                .or().apply("description ILIKE {0}", term));
        }
        query.orderByDesc("created_at").orderByDesc("id");
        return selectPage(new Page<>(page, 20), query);
    }

    @Select("SELECT * FROM ticket WHERE id=#{id} FOR UPDATE")
    TicketEntity lock(@Param("id") UUID id);

    @Select("SELECT 'ITSM-' || nextval('ticket_number_seq')")
    String nextNumber();

    @Select("SELECT to_regclass('public.ticket') IS NOT NULL")
    boolean ticketTableExists();

    @Select("SELECT count(*) FROM ticket WHERE assignee_id=#{userId} AND status NOT IN ('CLOSED','REJECTED')")
    long countAssignedNotClosed(@Param("userId") UUID userId);

    @Select("SELECT count(*) FROM ticket WHERE assignee_id=#{userId} AND status='RESOLVED'")
    long countResolved(@Param("userId") UUID userId);

    @Select("SELECT count(*) FROM ticket WHERE assignee_id=#{userId} AND status IN ('NEW','IN_PROGRESS','ON_HOLD')")
    long countUnresolved(@Param("userId") UUID userId);

    @Select("SELECT EXISTS(SELECT 1 FROM ticket WHERE id=#{ticketId} AND requester_id=#{requesterId})")
    boolean requesterHasTicket(@Param("ticketId") UUID ticketId, @Param("requesterId") UUID requesterId);

    @Select("SELECT EXISTS(SELECT 1 FROM ticket WHERE id=#{ticketId})")
    boolean ticketExists(@Param("ticketId") UUID ticketId);

    default long assignedNotClosed(UUID userId) {
        return ticketTableExists() ? countAssignedNotClosed(userId) : 0;
    }

    default long count(UUID userId, boolean resolved) {
        if (!ticketTableExists()) return 0;
        return resolved ? countResolved(userId) : countUnresolved(userId);
    }

    default boolean existsForRequester(UUID ticketId, UUID requesterId) {
        return ticketTableExists() && requesterHasTicket(ticketId, requesterId);
    }

    default boolean existsById(UUID ticketId) {
        return ticketTableExists() && ticketExists(ticketId);
    }
}
