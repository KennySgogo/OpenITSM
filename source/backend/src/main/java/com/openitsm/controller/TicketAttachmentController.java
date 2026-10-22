package com.openitsm.controller;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.UploadTicketAttachmentRequest;
import com.openitsm.service.TicketAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class TicketAttachmentController {
    private final TicketAttachmentService service;
    public TicketAttachmentController(TicketAttachmentService service) { this.service = service; }

    @PostMapping(value = "/api/v1/tickets/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable UUID id, MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().size() != 1 || request.getFiles("file").size() != 1
            || request.getParameterMap().size() != 1 || request.getParameterValues("visibility") == null
            || request.getParameterValues("visibility").length != 1)
            throw BusinessException.field("file", "请仅提供一个 file 文件和一个 visibility 字段");
        var result = service.upload(RequestAuthentication.from(request), id,
            new UploadTicketAttachmentRequest(request.getFile("file"), request.getParameter("visibility")),
            UUID.fromString((String) request.getAttribute("requestId")));
        var response = ResponseEntity.ok();

        return response.body(ApiResponse.of(request, result));
    }

    @GetMapping("/api/v1/tickets/{id}/attachments")
    public ApiResponse<?> list(@PathVariable UUID id, @RequestParam(required = false) String page, HttpServletRequest request) {
        return ApiResponse.of(request, service.list(RequestAuthentication.from(request), id, page));
    }

    @GetMapping("/api/v1/attachments/{attachmentId}")
    public ResponseEntity<?> download(@PathVariable UUID attachmentId, HttpServletRequest request) {
        var item = service.download(RequestAuthentication.from(request), attachmentId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(item.size())
            .header("Content-Disposition", ContentDisposition.attachment().filename(item.fileName(), StandardCharsets.UTF_8).build().toString())
            .body(item.content());
    }
}
