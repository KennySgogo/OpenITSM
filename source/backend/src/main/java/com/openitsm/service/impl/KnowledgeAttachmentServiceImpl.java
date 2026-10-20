package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.config.AttachmentConfig;
import com.openitsm.dto.UploadKnowledgeAttachmentRequest;
import com.openitsm.entity.KnowledgeAttachmentEntity;
import com.openitsm.entity.KnowledgeArticleEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.KnowledgeAttachmentMapper;
import com.openitsm.mapper.KnowledgeArticleMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.SessionService;
import com.openitsm.service.KnowledgeAttachmentService;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.AttachmentDownloadVO;
import com.openitsm.vo.KnowledgeAttachmentPageVO;
import com.openitsm.vo.KnowledgeAttachmentVO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class KnowledgeAttachmentServiceImpl implements KnowledgeAttachmentService {
    private final KnowledgeAttachmentMapper attachments;
    private final KnowledgeArticleMapper articles;
    private final UserMapper users;
    private final IdempotencyService idem;
    private final SessionService sessions;
    private final Clock clock;
    private final Path root;

    public KnowledgeAttachmentServiceImpl(KnowledgeAttachmentMapper attachments, KnowledgeArticleMapper articles, UserMapper users,
        IdempotencyService idem, SessionService sessions, Clock clock, AttachmentConfig config) throws IOException {
        this.attachments = attachments; this.articles = articles; this.users = users; this.idem = idem;
        this.sessions = sessions; this.clock = clock;
        Files.createDirectories(config.root().resolve("knowledge"));
        this.root = config.root().resolve("knowledge").toRealPath();
    }

    public boolean storageReady() {
        return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(root) && Files.isWritable(root);
    }

    @Override
    @Transactional
    public ActionVO upload(AuthContext context, UUID articleId, UploadKnowledgeAttachmentRequest body, String version, String key) {
        long expectedVersion = Values.version(version);
        UUID requestKey = idem.key(key);
        var file = body.file();
        if (file == null || file.isEmpty()) throw BusinessException.field("file", "请选择非空文件");
        if (file.getSize() > (100L * 1024 * 1024)) throw tooLarge();
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) throw BusinessException.field("file", "文件名不能为空");
        String contentHash;
        try (var input = file.getInputStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; long size = 0; int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > (100L * 1024 * 1024)) throw tooLarge();
                digest.update(buffer, 0, read);
            }
            if (size != file.getSize()) throw BusinessException.field("file", "文件内容不完整");
            contentHash = HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) { throw unavailable(); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        String operation = "POST /api/v1/knowledge-articles/" + articleId + "/attachments";
        String hash = idem.hash(Map.of("fileName", originalName, "contentHash", contentHash), version);
        UUID actorId = context.user().id;
        idem.reserve(actorId, operation, requestKey);
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var article = articles.lock(articleId);
        requireVisible(actor, article);
        requireAuthor(actor, article);
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        if (article.version != expectedVersion) throw new BusinessException(409, "VERSION_CONFLICT", "知识已更新，请刷新后重试");
        requireDraft(article);
        var attachment = new KnowledgeAttachmentEntity();
        attachment.id = UUID.randomUUID(); attachment.articleId = articleId; attachment.uploaderId = actorId;
        attachment.fileName = safeFileName(originalName); attachment.size = file.getSize(); attachment.contentHash = contentHash;
        attachment.createdAt = clock.instant();
        Path target = root.resolve(attachment.id.toString());
        try {
            // CREATE_NEW prevents replacing a stored file or following a pre-existing symbolic link.
            var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(target); }
                        catch (IOException ex) {
                            LoggerFactory.getLogger(KnowledgeAttachmentServiceImpl.class).error("Failed attachment cleanup id={}", attachment.id);
                        }
                    }
                    // Unknown commit outcome is retained for manual reconciliation, never guessed as rollback.
                }
            });
            try (output; var input = file.getInputStream()) { input.transferTo(output); }
        } catch (IOException ex) { throw unavailable(); }
        attachments.insert(attachment);
        article.version++; article.updatedAt = clock.instant();
        articles.updateById(article);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, articleId, article.version, 200);
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeAttachmentPageVO list(AuthContext context, UUID articleId, String page) {
        requireVisible(context.user(), articles.selectById(articleId));
        int number;
        try { number = page == null ? 1 : Integer.parseInt(page); }
        catch (NumberFormatException ex) { throw new BusinessException(400, "INVALID_QUERY", "查询参数无效"); }
        if (number < 1) throw new BusinessException(400, "INVALID_QUERY", "查询参数无效");
        var result = attachments.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(number, 20),
            new QueryWrapper<KnowledgeAttachmentEntity>().eq("article_id", articleId).isNull("removed_at").orderByAsc("created_at", "id"));
        var ids = result.getRecords().stream().map(item -> item.uploaderId).toList();
        Map<UUID, String> names = ids.isEmpty() ? Map.of() : users.selectList(new QueryWrapper<UserEntity>().in("id", ids)).stream()
            .collect(Collectors.toMap(user -> user.id, user -> user.name));
        var items = result.getRecords().stream().map(item -> new KnowledgeAttachmentVO(item.id, item.fileName, item.size,
            item.uploaderId, names.get(item.uploaderId), item.createdAt)).toList();
        return new KnowledgeAttachmentPageVO(items, number, 20, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public AttachmentDownloadVO download(AuthContext context, UUID attachmentId) {
        var item = attachments.selectById(attachmentId);
        if (item == null || item.removedAt != null) throw missing();
        requireVisible(context.user(), articles.selectById(item.articleId));
        Path path = root.resolve(item.id.toString());
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != item.size) throw unavailable();
            return new AttachmentDownloadVO(item.fileName, item.size,
                new InputStreamResource(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)));
        } catch (IOException ex) { throw unavailable(); }
    }

    private String safeFileName(String input) {
        String name = input.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip();
        if (name.isBlank() || name.equals(".") || name.equals("..")) return "attachment";
        return name;
    }
    private void requireVisible(UserEntity actor, KnowledgeArticleEntity article) {
        if (article == null || (!"PUBLISHED".equals(article.status) && !"ADMIN".equals(actor.role)
            && !("ENGINEER".equals(actor.role) && actor.id.equals(article.authorId)))) throw missing();
    }
    private void requireAuthor(UserEntity actor, KnowledgeArticleEntity article) {
        if (!"ENGINEER".equals(actor.role) || !actor.id.equals(article.authorId))
            throw new BusinessException(403, "FORBIDDEN", "仅原作者工程师可维护草稿附件");
    }
    private void requireDraft(KnowledgeArticleEntity article) {
        if (!"DRAFT".equals(article.status)) throw new BusinessException(409, "INVALID_STATE", "请先下架知识再修改附件");
    }
    @Override
    @Transactional
    public ActionVO remove(AuthContext context, UUID articleId, UUID attachmentId, String version, String key) {
        long expected = Values.version(version);
        UUID requestKey = idem.key(key), actorId = context.user().id;
        String operation = "DELETE /api/v1/knowledge-articles/" + articleId + "/attachments/" + attachmentId;
        String hash = idem.hash(null, version);
        idem.reserve(actorId, operation, requestKey);
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var article = articles.lock(articleId);
        requireVisible(actor, article); requireAuthor(actor, article);
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        if (article.version != expected) throw new BusinessException(409, "VERSION_CONFLICT", "知识已更新，请刷新后重新确认");
        requireDraft(article);
        var item = attachments.selectById(attachmentId);
        if (item == null || item.removedAt != null || !articleId.equals(item.articleId)) throw missing();
        item.removedAt = clock.instant(); item.removedBy = actorId; attachments.updateById(item);
        article.version++; article.updatedAt = clock.instant(); articles.updateById(article);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, articleId, article.version, 200);
    }
    private BusinessException missing() { return new BusinessException(404, "RESOURCE_NOT_FOUND", "附件或知识不存在或不可访问"); }
    private BusinessException unavailable() { return new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "附件存储暂不可用，请稍后重试"); }
    private BusinessException tooLarge() { return new BusinessException(413, "REQUEST_TOO_LARGE", "单个知识附件不能超过 100 MiB"); }
}
