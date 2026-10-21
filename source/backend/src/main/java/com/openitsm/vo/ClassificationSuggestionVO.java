package com.openitsm.vo;

import java.util.UUID;

public record ClassificationSuggestionVO(UUID businessTypeId, String reason, String suggestedPriority) {}
