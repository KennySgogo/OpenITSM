package com.openitsm.mapper;

import com.openitsm.entity.IdempotencyRecordEntity;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdempotencyMapper {
    @Select("SELECT actor_id, operation, \"key\", request_hash, state, resource_id, accepted_version, response_status, created_at FROM idempotency_record WHERE actor_id=#{actor} AND operation=#{operation} AND \"key\"=#{key}")
    @ConstructorArgs({
        @Arg(column = "actor_id", javaType = UUID.class),
        @Arg(column = "operation", javaType = String.class),
        @Arg(column = "key", javaType = UUID.class),
        @Arg(column = "request_hash", javaType = String.class),
        @Arg(column = "state", javaType = String.class),
        @Arg(column = "resource_id", javaType = UUID.class),
        @Arg(column = "accepted_version", javaType = long.class),
        @Arg(column = "response_status", javaType = int.class),
        @Arg(column = "created_at", javaType = Instant.class)
    })
    IdempotencyRecordEntity find(@Param("actor") UUID actor, @Param("operation") String operation, @Param("key") UUID key);

    @Insert("INSERT INTO idempotency_record(actor_id,operation,\"key\",request_hash,state,resource_id,accepted_version,response_status,created_at) VALUES (#{actor},#{operation},#{key},#{hash},'SUCCESS',#{target},#{version},#{status},#{now})")
    int insert(@Param("actor") UUID actor, @Param("operation") String operation, @Param("key") UUID key,
        @Param("hash") String hash, @Param("target") UUID target, @Param("version") long version,
        @Param("status") int status, @Param("now") Instant now);
}
