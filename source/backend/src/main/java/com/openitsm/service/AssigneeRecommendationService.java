package com.openitsm.service;

import com.openitsm.dto.AssigneeRecommendationRequest;
import com.openitsm.vo.AssigneeRecommendationVO;
import java.util.UUID;

public interface AssigneeRecommendationService {
    AssigneeRecommendationVO recommend(AuthContext context, AssigneeRecommendationRequest body);
    AssigneeRecommendationVO recommend(AuthContext context, UUID ticketId);
}
