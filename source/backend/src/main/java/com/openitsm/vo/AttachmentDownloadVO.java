package com.openitsm.vo;

import org.springframework.core.io.Resource;

public record AttachmentDownloadVO(String fileName, long size, Resource content) {}
