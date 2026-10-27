package com.openitsm.service;

import com.openitsm.vo.ReplyDraftVO;
import java.util.UUID;

public interface ReplyDraftService {
    ReplyDraftVO generate(AuthContext context, UUID ticketId);
}
