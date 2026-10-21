package com.openitsm.dto;

import org.springframework.web.multipart.MultipartFile;

public record UploadKnowledgeAttachmentRequest(MultipartFile file) {}
