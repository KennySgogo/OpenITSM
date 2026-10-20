package com.openitsm;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.DateRangeUtils;
import com.openitsm.mapper.TicketMapper;
import java.time.Instant;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModuleExtensionContractTest {
    @Test void sharedDateRangeUsesShanghaiDaysAndExclusiveNextMidnight() {
        assertThat(DateRangeUtils.parse("2026-10-19", "2026-10-19")).containsExactly(
            Instant.parse("2026-10-18T16:00:00Z"), Instant.parse("2026-10-19T16:00:00Z"));
        assertThat(DateRangeUtils.parse(null, null)).containsExactly(null, null);
        assertThat(DateRangeUtils.parse(null, "2024-02-29")[1]).isEqualTo(Instant.parse("2024-02-29T16:00:00Z"));
    }

    @Test void invalidDatesAndReversedRangesAreQueryErrors() {
        for (String value : new String[] { "2026-02-29", "2026-10-32", "2026-1-1", "", "bad" })
            assertThatThrownBy(() -> DateRangeUtils.parse(value, null)).isInstanceOfSatisfying(BusinessException.class,
                error -> { assertThat(error.status).isEqualTo(400); assertThat(error.code).isEqualTo("INVALID_QUERY"); });
        assertThatThrownBy(() -> DateRangeUtils.parse("2026-10-20", "2026-10-19")).isInstanceOf(BusinessException.class);
    }

    @Test void annotatedAggregationXmlLoadsBeforeDatabaseExecution() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(TicketMapper.class);
        var parameters = new HashMap<String, Object>(); parameters.put("from", null); parameters.put("to", null);
        for (String method : new String[] { "statusCounts", "typeCounts", "assigneeLoads", "resolutionMetrics" }) {
            String sql = configuration.getMappedStatement(TicketMapper.class.getName() + "." + method).getBoundSql(parameters).getSql();
            assertThat(sql).doesNotContain("&gt;", "&lt;", "&quot;", "<script>");
        }
    }
}
