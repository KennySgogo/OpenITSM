package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openitsm.entity.TicketReplyEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketReplyMapper extends BaseMapper<TicketReplyEntity> {
    default Page<TicketReplyEntity> findPage(UUID ticketId, boolean employee, int page) {
        var query = new QueryWrapper<TicketReplyEntity>().eq("ticket_id", ticketId);
        if (employee) query.eq("visibility", "PUBLIC");
        query.orderByAsc("created_at", "id");
        return selectPage(new Page<>(page, 20), query);
    }
}
