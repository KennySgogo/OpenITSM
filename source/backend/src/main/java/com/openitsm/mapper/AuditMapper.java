package com.openitsm.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    @Insert("INSERT INTO audit_event(id,actor_id,action,object_type,object_id,before_after,reason,outcome,request_id,created_at) VALUES (#{id},#{actor},#{action},'TICKET',#{target},CAST(#{changes} AS jsonb),#{reason},'SUCCESS',#{requestId},#{now})")
    int insertTicket(@Param("id") UUID id, @Param("actor") UUID actor, @Param("action") String action,
        @Param("target") UUID target, @Param("changes") String changes, @Param("reason") String reason,
        @Param("requestId") UUID requestId, @Param("now") Instant now);

    default int insert(UUID actor, String action, UUID target, String changes, String reason, UUID requestId, Instant now) {
        return insertWithId(UUID.randomUUID(), actor, action, target, changes, reason, requestId, now);
    }

    @Insert("INSERT INTO audit_event(id,actor_id,action,object_type,object_id,before_after,reason,outcome,request_id,created_at) VALUES (#{id},#{actor},#{action},'USER',#{target},CAST(#{changes} AS jsonb),#{reason},'SUCCESS',#{requestId},#{now})")
    int insertWithId(@Param("id") UUID id, @Param("actor") UUID actor, @Param("action") String action,
        @Param("target") UUID target, @Param("changes") String changes, @Param("reason") String reason,
        @Param("requestId") UUID requestId, @Param("now") Instant now);
}
