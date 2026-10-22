package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AttachmentConfig;
import com.openitsm.dto.UploadTicketAttachmentRequest;
import com.openitsm.entity.TicketAttachmentEntity;
import com.openitsm.entity.TicketEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.TicketAttachmentMapper;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuditService;
import com.openitsm.service.AuthContext;
import com.openitsm.service.SessionService;
import com.openitsm.service.TicketAttachmentService;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.AttachmentDownloadVO;
import com.openitsm.vo.TicketAttachmentPageVO;
import com.openitsm.vo.TicketAttachmentVO;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TicketAttachmentServiceImpl implements TicketAttachmentService {
    private final TicketAttachmentMapper attachments;
    private final TicketMapper tickets;
    private final UserMapper users;
    private final AuditService audit;
    private final SessionService sessions;
    private final Clock clock;
    private final Path root;

    public TicketAttachmentServiceImpl(TicketAttachmentMapper attachments, TicketMapper tickets, UserMapper users,
        AuditService audit, SessionService sessions, Clock clock, AttachmentConfig config) throws IOException {
        this.attachments = attachments; this.tickets = tickets; this.users = users;
        this.audit = audit; this.sessions = sessions; this.clock = clock;
        Files.createDirectories(config.root());
        this.root = config.root().toRealPath();
    }

    @Override
    @Transactional
    public ActionVO upload(AuthContext context, UUID ticketId, UploadTicketAttachmentRequest body, UUID requestId) {
        var file = body.file();
        if (file == null || file.isEmpty()) throw BusinessException.field("file", "请选择非空文件");
        if (file.getSize() > AttachmentConfig.MAX_FILE_SIZE) throw tooLarge();
        if (body.visibility() == null || !Set.of("PUBLIC", "INTERNAL").contains(body.visibility()))
            throw BusinessException.field("visibility", "请选择公开或内部可见性");
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) throw BusinessException.field("file", "文件名不能为空");
        String contentHash;
        try (var input = file.getInputStream()) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; long size = 0; int read;
            while ((read = input.read(buffer)) != -1) {
                size += read;
                if (size > AttachmentConfig.MAX_FILE_SIZE) throw tooLarge();
                digest.update(buffer, 0, read);
            }
            if (size != file.getSize()) throw BusinessException.field("file", "文件内容不完整");
            contentHash = HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) { throw unavailable(); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        UUID actorId = context.user().id;
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var ticket = tickets.lock(ticketId);
        requireVisible(actor, ticket);
        boolean internal = "INTERNAL".equals(body.visibility());
        if (internal && "EMPLOYEE".equals(actor.role)) throw new BusinessException(403, "FORBIDDEN", "无操作权限");
        if (!Set.of("NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED").contains(ticket.status))
            throw new BusinessException(409, "INVALID_STATE", "工单已关闭，不能上传附件，请新建工单");
        var attachment = new TicketAttachmentEntity();
        attachment.id = UUID.randomUUID(); attachment.ticketId = ticketId; attachment.uploaderId = actorId;
        attachment.fileName = safeFileName(originalName); attachment.size = file.getSize(); attachment.contentHash = contentHash;
        attachment.visibility = body.visibility(); attachment.createdAt = clock.instant();
        Path target = root.resolve(attachment.id.toString());
        try {
            // CREATE_NEW prevents replacing a stored file or following a pre-existing symbolic link.
            var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(target); }
                        catch (IOException ex) {
                            LoggerFactory.getLogger(TicketAttachmentServiceImpl.class).error("Failed attachment cleanup id={}", attachment.id);
                        }
                    }
                    // Unknown commit outcome is retained for manual reconciliation, never guessed as rollback.
                }
            });
            try (output; var input = file.getInputStream()) { input.transferTo(output); }
        } catch (IOException ex) { throw unavailable(); }
        attachments.insert(attachment);
        ticket.version++;
        tickets.updateById(ticket);
        audit.appendTicket(actorId, internal ? "TICKET_INTERNAL_ATTACHMENT" : "TICKET_ATTACHMENT", ticketId,
            Map.of("attachmentId", attachment.id, "fileName", attachment.fileName, "size", attachment.size), null, requestId);
        sessions.touch(context.session().hash());
        return new ActionVO(ticketId);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketAttachmentPageVO list(AuthContext context, UUID ticketId, String page) {
        requireVisible(context.user(), tickets.selectById(ticketId));
        int number;
        try { number = page == null ? 1 : Integer.parseInt(page); }
        catch (NumberFormatException ex) { throw new BusinessException(400, "INVALID_QUERY", "查询参数无效"); }
        if (number < 1) throw new BusinessException(400, "INVALID_QUERY", "查询参数无效");
        var result = attachments.findPage(ticketId, "EMPLOYEE".equals(context.user().role), number);
        var ids = result.getRecords().stream().map(item -> item.uploaderId).toList();
        Map<UUID, String> names = ids.isEmpty() ? Map.of() : users.selectList(new QueryWrapper<UserEntity>().in("id", ids)).stream()
            .collect(Collectors.toMap(user -> user.id, user -> user.name));
        var items = result.getRecords().stream().map(item -> new TicketAttachmentVO(item.id, item.fileName, item.size,
            item.visibility, item.uploaderId, names.get(item.uploaderId), item.createdAt)).toList();
        return new TicketAttachmentPageVO(items, number, 20, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public AttachmentDownloadVO download(AuthContext context, UUID attachmentId) {
        var item = attachments.selectById(attachmentId);
        if (item == null) throw missing();
        requireVisible(context.user(), tickets.selectById(item.ticketId));
        if ("EMPLOYEE".equals(context.user().role) && "INTERNAL".equals(item.visibility)) throw missing();
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
    private void requireVisible(UserEntity actor, TicketEntity ticket) {
        if (ticket == null || ("EMPLOYEE".equals(actor.role) && !actor.id.equals(ticket.requesterId))) throw missing();
    }
    private BusinessException missing() { return new BusinessException(404, "RESOURCE_NOT_FOUND", "附件或工单不存在或不可访问"); }
    private BusinessException unavailable() { return new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "附件存储暂不可用，请稍后重试"); }
    private BusinessException tooLarge() { return new BusinessException(413, "REQUEST_TOO_LARGE", "单个附件不能超过 10 MiB"); }
}
