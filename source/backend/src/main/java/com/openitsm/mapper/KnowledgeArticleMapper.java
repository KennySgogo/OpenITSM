package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openitsm.entity.KnowledgeArticleEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeArticleMapper extends BaseMapper<KnowledgeArticleEntity> {
    @Select("SELECT * FROM knowledge_article WHERE id=#{id} FOR UPDATE")
    KnowledgeArticleEntity lock(@Param("id") UUID id);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM knowledge_article WHERE id=#{id}
          AND (status='PUBLISHED' OR #{role}='ADMIN' OR (#{role}='ENGINEER' AND author_id=#{actor})))
        """)
    boolean visible(@Param("id") UUID id, @Param("actor") UUID actor, @Param("role") String role);

    default Page<KnowledgeArticleEntity> findPage(UUID actor, String view, int page, String q, String category) {
        var query = new QueryWrapper<KnowledgeArticleEntity>();
        if ("PUBLISHED".equals(view)) query.eq("status", "PUBLISHED");
        if ("MINE".equals(view)) query.eq("author_id", actor);
        if (category != null) query.eq("category", category);
        if (q != null) {
            String term = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            query.and(it -> it.apply("title ILIKE {0}", term).or().apply("content ILIKE {0}", term));
        }
        query.orderByDesc("updated_at").orderByDesc("id");
        return selectPage(new Page<>(page, 20), query);
    }
}
