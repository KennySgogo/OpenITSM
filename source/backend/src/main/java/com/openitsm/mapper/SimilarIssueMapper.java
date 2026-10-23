package com.openitsm.mapper;

import com.openitsm.vo.SimilarIssueSourceVO;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SimilarIssueMapper {
    @Select("""
        <script>
        WITH sources AS (
          SELECT 'TICKET' AS "sourceType", id AS "sourceId", number, title,
            description AS content, resolution_summary AS "resolutionSummary"
          FROM ticket WHERE status IN ('RESOLVED','CLOSED') AND id != #{currentId}
          UNION ALL
          SELECT 'KNOWLEDGE',id,NULL,title,content,NULL FROM knowledge_article WHERE status='PUBLISHED'
        ), ranked AS (
          SELECT s.*, (SELECT count(*) FROM unnest(ARRAY[
            <foreach collection="terms" item="term" separator=",">#{term}</foreach>
          ]::text[]) AS words(term) WHERE strpos(lower(s.title || ' ' || s.content || ' ' || coalesce(s."resolutionSummary",'')),lower(term)) > 0) AS score
          FROM sources s
        ) SELECT * FROM ranked WHERE score > 0 ORDER BY score DESC,"sourceType","sourceId" LIMIT 10
        </script>
        """)
    List<SimilarIssueSourceVO> candidates(@Param("currentId") UUID currentId, @Param("terms") List<String> terms);
}
