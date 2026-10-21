package com.openitsm.mapper;

import com.openitsm.entity.AiConnectionStatusEntity;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiConnectionStatusMapper {
    @Select("SELECT * FROM ai_connection_status WHERE kind=#{kind}")
    AiConnectionStatusEntity find(String kind);

    @Insert("""
        INSERT INTO ai_connection_status(kind,config_version,status,occurred_at,attempt_id)
        VALUES(#{kind},#{version},'RUNNING',#{now},#{attempt})
        ON CONFLICT(kind) DO UPDATE SET config_version=excluded.config_version,status='RUNNING',
            occurred_at=excluded.occurred_at,attempt_id=excluded.attempt_id,output_mode=NULL
        """)
    void begin(@Param("kind") String kind, @Param("version") long version, @Param("now") Instant now, @Param("attempt") UUID attempt);

    @Update("UPDATE ai_connection_status SET status=#{status},occurred_at=#{now} WHERE kind=#{kind} AND attempt_id=#{attempt}")
    void finish(@Param("kind") String kind, @Param("attempt") UUID attempt, @Param("status") String status, @Param("now") Instant now);
    @Update("UPDATE ai_connection_status SET status=#{status},occurred_at=#{now},output_mode=#{mode} WHERE kind='TEST' AND attempt_id=#{attempt}")
    void finishTest(@Param("attempt") UUID attempt, @Param("status") String status, @Param("now") Instant now, @Param("mode") String mode);
}
