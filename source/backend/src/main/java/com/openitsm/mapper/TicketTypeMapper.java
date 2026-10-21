package com.openitsm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openitsm.entity.TicketTypeEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TicketTypeMapper extends BaseMapper<TicketTypeEntity> {
    @Select("SELECT * FROM ticket_type WHERE id=#{id} FOR UPDATE")
    TicketTypeEntity lock(@Param("id") UUID id);
}
