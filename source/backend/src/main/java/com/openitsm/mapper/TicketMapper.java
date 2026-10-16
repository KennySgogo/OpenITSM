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
    default Page<TicketEntity> findPage(UUID actor, String view, int page, String status, String businessType, String q) {
        var query = new QueryWrapper<TicketEntity>();
        switch (view) {
            case "MINE" -> query.eq("requester_id", actor);
            case "ASSIGNED" -> query.eq("assignee_id", actor);
            case "UNASSIGNED" -> query.isNull("assignee_id");
            default -> { /* ALL; validated by the service. */ }
        }
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

    @Select("SELECT count(*) FROM ticket WHERE assignee_id=#{userId} AND status <> 'CLOSED'")
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
