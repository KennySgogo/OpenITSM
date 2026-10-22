package com.openitsm.controller;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.UploadKnowledgeAttachmentRequest;
import com.openitsm.service.KnowledgeAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class KnowledgeAttachmentController {
    private final KnowledgeAttachmentService service;
    public KnowledgeAttachmentController(KnowledgeAttachmentService service) { this.service = service; }

    @PostMapping(value = "/api/v1/knowledge-articles/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable UUID id, MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1 || !request.getParameterMap().isEmpty())
            throw BusinessException.field("file", "请仅提供一个 file 文件，不提供其他字段");
        return action(request, service.upload(RequestAuthentication.from(request), id,
            new UploadKnowledgeAttachmentRequest(request.getFile("file"))));
    }

    @DeleteMapping("/api/v1/knowledge-articles/{id}/attachments/{attachmentId}")
    public ResponseEntity<?> remove(@PathVariable UUID id, @PathVariable UUID attachmentId, HttpServletRequest request) {
        return action(request, service.remove(RequestAuthentication.from(request), id, attachmentId));
    }

    @GetMapping("/api/v1/knowledge-articles/{id}/attachments")
    public ApiResponse<?> list(@PathVariable UUID id, @RequestParam(required = false) String page, HttpServletRequest request) {
        return ApiResponse.of(request, service.list(RequestAuthentication.from(request), id, page));
    }

    @GetMapping("/api/v1/knowledge-attachments/{attachmentId}")
    public ResponseEntity<?> download(@PathVariable UUID attachmentId, HttpServletRequest request) {
        var item = service.download(RequestAuthentication.from(request), attachmentId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(item.size())
            .header("Content-Disposition", ContentDisposition.attachment().filename(item.fileName(), StandardCharsets.UTF_8).build().toString())
            .body(item.content());
    }

    private ResponseEntity<?> action(HttpServletRequest request, com.openitsm.vo.ActionVO result) {
        var response = ResponseEntity.ok();

        return response.body(ApiResponse.of(request, result));
    }
}
