package com.openitsm.mapper;

import java.time.Instant;
import java.util.UUID;
import com.openitsm.entity.NotificationTaskEntity;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper {
    @Insert("INSERT INTO notification_task(id,event_id,recipient_id,template,status,next_attempt_at,sealed_payload,token_generation) VALUES (#{id},#{id},#{userId},#{purpose},'PENDING',#{dueAt},#{sealed},#{generation})")
    int insertAccountTask(@Param("id") UUID id, @Param("userId") UUID userId, @Param("purpose") String purpose,
        @Param("dueAt") Instant dueAt, @Param("sealed") String sealed, @Param("generation") long generation);

    @Insert("""
        INSERT INTO notification_task(id,event_id,recipient_id,template,status,next_attempt_at,
          sealed_payload,token_generation,ticket_id,ticket_action)
        VALUES (#{id},#{eventId},#{userId},'TICKET','PENDING',#{dueAt},'',0,#{ticketId},#{action})
        """)
    int insertTicketTask(@Param("id") UUID id, @Param("eventId") UUID eventId, @Param("userId") UUID userId,
        @Param("dueAt") Instant dueAt, @Param("ticketId") UUID ticketId, @Param("action") String action);

    @Update("UPDATE notification_task SET status='FAILED',error_code='TASK_CANCELLED',lease_until=NULL WHERE recipient_id=#{userId} AND template=#{purpose} AND status IN ('PENDING','RUNNING')")
    int cancelAccountTasks(@Param("userId") UUID userId, @Param("purpose") String purpose);

    @Select(value = """
        WITH candidate AS (
            SELECT id FROM notification_task
            WHERE channel='EMAIL' AND attempts < #{maxAttempts}
              AND (template IN ('INVITE','RESET') OR (template='TICKET' AND #{ticketEnabled}))
              AND ((status='PENDING' AND next_attempt_at <= #{now})
                OR (status='RUNNING' AND lease_until <= #{now}))
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
        )
        UPDATE notification_task task
        SET status='RUNNING',attempts=task.attempts+1,lease_until=#{leaseUntil},error_code=NULL
        FROM candidate WHERE task.id=candidate.id
        RETURNING task.id,task.recipient_id,task.template,task.sealed_payload,
                  task.token_generation,task.attempts,task.lease_until,task.ticket_id,task.ticket_action
        """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
        @Arg(column="id",javaType=UUID.class), @Arg(column="recipient_id",javaType=UUID.class),
        @Arg(column="template",javaType=String.class), @Arg(column="sealed_payload",javaType=String.class),
        @Arg(column="token_generation",javaType=long.class), @Arg(column="attempts",javaType=int.class),
        @Arg(column="lease_until",javaType=Instant.class),
        @Arg(column="ticket_id",javaType=UUID.class), @Arg(column="ticket_action",javaType=String.class)
    })
    NotificationTaskEntity claim(@Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil,
                                 @Param("maxAttempts") int maxAttempts, @Param("ticketEnabled") boolean ticketEnabled);

    default NotificationTaskEntity claim(Instant now, Instant leaseUntil, int maxAttempts) {
        return claim(now, leaseUntil, maxAttempts, false);
    }

    @Select("""
        SELECT id,recipient_id,template,sealed_payload,token_generation,attempts,lease_until,ticket_id,ticket_action
        FROM notification_task WHERE id=#{id} AND status='RUNNING'
          AND attempts=#{attempt} AND lease_until > #{now} FOR UPDATE
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
        @Arg(column="id",javaType=UUID.class), @Arg(column="recipient_id",javaType=UUID.class),
        @Arg(column="template",javaType=String.class), @Arg(column="sealed_payload",javaType=String.class),
        @Arg(column="token_generation",javaType=long.class), @Arg(column="attempts",javaType=int.class),
        @Arg(column="lease_until",javaType=Instant.class),
        @Arg(column="ticket_id",javaType=UUID.class), @Arg(column="ticket_action",javaType=String.class)
    })
    NotificationTaskEntity lockClaim(@Param("id") UUID id, @Param("attempt") int attempt, @Param("now") Instant now);

    @Update("""
        UPDATE notification_task SET status=#{status},error_code=#{errorCode},
          next_attempt_at=#{nextAttempt},lease_until=NULL
        WHERE id=#{id} AND status='RUNNING' AND attempts=#{attempt}
        """)
    int finish(@Param("id") UUID id, @Param("attempt") int attempt, @Param("status") String status,
               @Param("errorCode") String errorCode, @Param("nextAttempt") Instant nextAttempt);

    @Update("""
        WITH exhausted AS (
            SELECT id FROM notification_task WHERE channel='EMAIL' AND attempts >= #{maxAttempts}
              AND (template IN ('INVITE','RESET') OR (template='TICKET' AND #{ticketEnabled}))
              AND ((status='RUNNING' AND lease_until <= #{now}) OR status='PENDING')
            ORDER BY next_attempt_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
        )
        UPDATE notification_task task SET status='TIMEOUT',error_code='ATTEMPTS_EXHAUSTED',lease_until=NULL
        FROM exhausted WHERE task.id=exhausted.id
        """)
    int expireExhausted(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
        @Param("ticketEnabled") boolean ticketEnabled);

    default int expireExhausted(Instant now, int maxAttempts) { return expireExhausted(now, maxAttempts, false); }
}
