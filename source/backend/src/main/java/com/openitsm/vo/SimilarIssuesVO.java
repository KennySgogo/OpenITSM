package com.openitsm.vo;

import java.util.List;
import java.util.UUID;

public record SimilarIssuesVO(List<Item> items) {
    public record Item(String sourceType, UUID sourceId, String number, String title, String excerpt, String reason) {}
}
