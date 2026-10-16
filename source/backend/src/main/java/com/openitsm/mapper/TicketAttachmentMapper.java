package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openitsm.entity.TicketAttachmentEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachmentEntity> {
    default Page<TicketAttachmentEntity> findPage(UUID ticketId, boolean employee, int page) {
        var query = new QueryWrapper<TicketAttachmentEntity>().eq("ticket_id", ticketId);
        if (employee) query.eq("visibility", "PUBLIC");
        query.orderByAsc("created_at", "id");
        return selectPage(new Page<>(page, 20), query);
    }
}
