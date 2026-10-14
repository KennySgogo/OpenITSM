package com.openitsm.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper {
    @Insert("INSERT INTO notification_task(id,event_id,recipient_id,template,status,next_attempt_at,sealed_payload,token_generation) VALUES (#{id},#{id},#{userId},#{purpose},'PENDING',#{dueAt},#{sealed},#{generation})")
    int insertAccountTask(@Param("id") UUID id, @Param("userId") UUID userId, @Param("purpose") String purpose,
        @Param("dueAt") Instant dueAt, @Param("sealed") String sealed, @Param("generation") long generation);

    @Update("UPDATE notification_task SET status='FAILED',error_code='TASK_CANCELLED' WHERE recipient_id=#{userId} AND template=#{purpose} AND status IN ('PENDING','RUNNING')")
    int cancelAccountTasks(@Param("userId") UUID userId, @Param("purpose") String purpose);
}
