package com.openitsm.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AttachmentConfig {
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    public static final long MAX_REQUEST_SIZE = MAX_FILE_SIZE + 64 * 1024;
    private final Path root;

    public AttachmentConfig(@Value("${openitsm.attachments.root:./.local/attachments}") String root) {
        if (root.isBlank()) throw new IllegalArgumentException("Attachment directory is required");
        this.root = Path.of(root).toAbsolutePath().normalize();
    }
    public Path root() { return root; }
}
