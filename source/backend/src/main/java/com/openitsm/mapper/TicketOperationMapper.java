package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openitsm.entity.TicketOperationEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketOperationMapper extends BaseMapper<TicketOperationEntity> {
    default Page<TicketOperationEntity> findPage(UUID ticketId, boolean employee, int page) {
        var query = new QueryWrapper<TicketOperationEntity>().eq("object_type", "TICKET").eq("object_id", ticketId)
            .eq("outcome", "SUCCESS").in("action", "TICKET_CLAIM", "TICKET_ASSIGNMENTS", "TICKET_TRANSFERS",
                "TICKET_SUSPENSIONS", "TICKET_RESUME", "TICKET_RESOLUTIONS", "TICKET_CLOSE", "TICKET_REOPEN",
                "TICKET_REJECTIONS", "TICKET_PRIORITY_CHANGES", "TICKET_CLASSIFICATIONS", "TICKET_REPLY", "TICKET_INTERNAL_NOTE", "TICKET_ATTACHMENT", "TICKET_INTERNAL_ATTACHMENT");
        if (employee) query.notIn("action", "TICKET_INTERNAL_NOTE", "TICKET_INTERNAL_ATTACHMENT");
        query.orderByAsc("created_at", "id");
        return selectPage(new Page<>(page, 20), query);
    }
}
