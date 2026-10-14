package com.openitsm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** PostgreSQL transaction-scoped advisory locks; the calling service owns the transaction. */
@Mapper
public interface TransactionLockMapper {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    Object acquire(@Param("key") String key);

    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended(#{key},0))")
    boolean tryAcquire(@Param("key") String key);

    default void lock(String namespace, String key) {
        acquire(namespace + ":" + key);
    }

    default boolean tryLock(String namespace, String key) {
        return tryAcquire(namespace + ":" + key);
    }
}
