package com.openitsm.vo;

import java.util.List;
import java.util.UUID;

public record ReplyDraftVO(String content, List<Source> sources, long basedOnVersion, boolean hasReferenceSources) {
    public record Source(String sourceType, UUID sourceId, String number, String title) {}
}
