package com.openitsm.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BootstrapMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM account_bootstrap)")
    boolean completed();

    @Insert("INSERT INTO account_bootstrap(completed_at) VALUES (#{now})")
    int complete(@Param("now") Instant now);
}
