package com.openitsm.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RateLimitMapper {
    @Select("SELECT COALESCE(SUM(count),0)::integer FROM auth_rate_bucket WHERE scope=#{scope} AND key_hash=#{keyHash} AND window_start>#{since}")
    int countSince(@Param("scope") String scope, @Param("keyHash") String keyHash, @Param("since") Instant since);

    @Select("SELECT MAX(window_start) FROM auth_rate_bucket WHERE scope=#{scope} AND key_hash=#{keyHash}")
    Instant lastRequest(@Param("scope") String scope, @Param("keyHash") String keyHash);

    @Insert("INSERT INTO auth_rate_bucket(scope,key_hash,window_start,count,expires_at) VALUES (#{scope},#{keyHash},#{now},1,#{expires}) ON CONFLICT(scope,key_hash,window_start) DO UPDATE SET count=auth_rate_bucket.count+1")
    int increment(@Param("scope") String scope, @Param("keyHash") String keyHash, @Param("now") Instant now, @Param("expires") Instant expires);
}
