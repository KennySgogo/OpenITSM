package com.openitsm.vo;

import java.util.List;
import java.util.UUID;

public record AssigneeRecommendationVO(List<Item> items) {
    public record Item(UUID assigneeId, String name, String reason) {}
}
