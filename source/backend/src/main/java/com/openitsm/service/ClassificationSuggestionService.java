package com.openitsm.service;

import com.openitsm.vo.ClassificationSuggestionVO;
import java.util.UUID;

public interface ClassificationSuggestionService {
    ClassificationSuggestionVO suggest(AuthContext context, UUID ticketId);
}
