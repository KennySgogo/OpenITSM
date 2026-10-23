package com.openitsm.vo;

import java.util.UUID;

/** Internal database projection; never returned directly to the browser. */
public record SimilarIssueSourceVO(String sourceType, UUID sourceId, String number, String title, String content, String resolutionSummary, long score) {}
