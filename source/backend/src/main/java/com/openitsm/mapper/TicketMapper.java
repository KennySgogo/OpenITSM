package com.openitsm.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** M01 integration boundary. M02 owns the ticket schema; no placeholder tickets are created here. */
@Mapper
public interface TicketMapper {
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
