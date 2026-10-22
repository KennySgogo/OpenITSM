package com.openitsm.service;

import com.openitsm.vo.ClassificationSuggestionVO;
import com.openitsm.dto.CreateTicketClassificationSuggestionRequest;
import java.util.UUID;

public interface ClassificationSuggestionService {
    ClassificationSuggestionVO suggest(AuthContext context, UUID ticketId);
    ClassificationSuggestionVO suggestBeforeCreate(AuthContext context, CreateTicketClassificationSuggestionRequest body);
}
