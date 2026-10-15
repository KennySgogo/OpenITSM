package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openitsm.entity.UserEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM app_user WHERE email_normalized=#{email} FOR UPDATE")
    UserEntity lockEmail(@Param("email") String email);

    @Select("SELECT * FROM app_user WHERE id=#{id} FOR UPDATE")
    UserEntity lock(@Param("id") UUID id);

    @Select("SELECT count(*) FROM app_user WHERE role='ADMIN' AND status='ACTIVE'")
    int activeAdmins();

    @Select("SELECT * FROM app_user WHERE role='ENGINEER' AND status='ACTIVE' ORDER BY name,id")
    List<UserEntity> activeEngineers();

    default Page<UserEntity> findPage(int page, String q, String role, String status) {
        var query = new QueryWrapper<UserEntity>();
        if (q != null) {
            String term = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            query.and(it -> it.apply("name ILIKE {0}", "%" + term + "%").or().apply("email_normalized ILIKE {0}", "%" + term + "%"));
        }
        if (role != null) query.eq("role", role);
        if (status != null) query.eq("status", status);
        query.orderByDesc("updated_at").orderByAsc("id");
        return selectPage(new Page<>(page, 20), query);
    }
}
