package com.openitsm.dto;

import org.springframework.web.multipart.MultipartFile;

public record UploadTicketAttachmentRequest(MultipartFile file, String visibility) {}
