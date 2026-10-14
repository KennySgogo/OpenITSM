package com.openitsm.mapper;

import com.openitsm.entity.AccountTokenEntity;
import com.openitsm.entity.AuthSessionEntity;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AuthMapper {
    @Select("SELECT token_hash, user_id, auth_version, created_at, last_user_activity_at, last_activity_ping_at, absolute_expires_at, revoked_at, csrf_hash FROM auth_session WHERE token_hash=#{hash}")
    @ConstructorArgs({
        @Arg(column = "token_hash", javaType = String.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "auth_version", javaType = Long.class),
        @Arg(column = "created_at", javaType = Instant.class),
        @Arg(column = "last_user_activity_at", javaType = Instant.class),
        @Arg(column = "last_activity_ping_at", javaType = Instant.class),
        @Arg(column = "absolute_expires_at", javaType = Instant.class),
        @Arg(column = "revoked_at", javaType = Instant.class),
        @Arg(column = "csrf_hash", javaType = String.class)
    })
    AuthSessionEntity findSession(@Param("hash") String hash);

    @Insert("INSERT INTO auth_session(token_hash,user_id,auth_version,created_at,last_user_activity_at,absolute_expires_at,csrf_hash) VALUES (#{session.hash},#{session.userId},#{session.authVersion},#{session.createdAt},#{session.activityAt},#{session.expiresAt},#{session.csrfHash})")
    int insertSession(@Param("session") AuthSessionEntity session);

    @Update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,#{now}) WHERE token_hash=#{hash}")
    int revoke(@Param("hash") String hash, @Param("now") Instant now);

    @Update("UPDATE auth_session SET revoked_at=COALESCE(revoked_at,#{now}) WHERE user_id=#{userId}")
    int revokeAll(@Param("userId") UUID userId, @Param("now") Instant now);

    @Update("UPDATE auth_session SET last_user_activity_at=#{now} WHERE token_hash=#{hash} AND revoked_at IS NULL")
    int touch(@Param("hash") String hash, @Param("now") Instant now);

    @Update("UPDATE auth_session SET last_user_activity_at=#{now},last_activity_ping_at=#{now} WHERE token_hash=#{hash} AND revoked_at IS NULL AND (last_activity_ping_at IS NULL OR last_activity_ping_at<=#{earliestPing})")
    boolean updateActivity(@Param("hash") String hash, @Param("now") Instant now, @Param("earliestPing") Instant earliestPing);

    @Select("SELECT token_hash, user_id, purpose, expires_at, used_at, generation FROM account_token WHERE token_hash=#{hash} AND purpose=#{purpose}")
    @ConstructorArgs({
        @Arg(column = "token_hash", javaType = String.class),
        @Arg(column = "user_id", javaType = UUID.class),
        @Arg(column = "purpose", javaType = String.class),
        @Arg(column = "expires_at", javaType = Instant.class),
        @Arg(column = "used_at", javaType = Instant.class),
        @Arg(column = "generation", javaType = long.class)
    })
    AccountTokenEntity findToken(@Param("hash") String hash, @Param("purpose") String purpose);

    @Select("SELECT COALESCE(MAX(generation),0) + 1 FROM account_token WHERE user_id=#{userId} AND purpose=#{purpose}")
    long nextGeneration(@Param("userId") UUID userId, @Param("purpose") String purpose);

    @Insert("INSERT INTO account_token(token_hash,user_id,purpose,expires_at,generation) VALUES (#{token.hash},#{token.userId},#{token.purpose},#{token.expiresAt},#{token.generation})")
    int insertToken(@Param("token") AccountTokenEntity token);

    @Update("UPDATE account_token SET used_at=COALESCE(used_at,#{now}) WHERE user_id=#{userId} AND purpose=#{purpose}")
    int invalidateTokens(@Param("userId") UUID userId, @Param("purpose") String purpose, @Param("now") Instant now);
}
