package com.openitsm.service;

import com.openitsm.dto.SaveTicketTypeRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketTypeVO;
import java.util.List;
import java.util.UUID;

public interface TicketTypeService {
    List<TicketTypeVO> list(AuthContext context, String view);
    com.openitsm.vo.CreatedTicketTypeVO create(AuthContext context, SaveTicketTypeRequest body);
    ActionVO change(AuthContext context, UUID id, SaveTicketTypeRequest body);
}
