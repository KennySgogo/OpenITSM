package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.dto.SaveKnowledgeArticleRequest;
import com.openitsm.entity.KnowledgeArticleEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.KnowledgeArticleMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.KnowledgeArticleService;
import com.openitsm.service.SessionService;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.KnowledgeArticlePageVO;
import com.openitsm.vo.KnowledgeArticleVO;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeArticleServiceImpl implements KnowledgeArticleService {
    private final KnowledgeArticleMapper articles;
    private final UserMapper users;
    private final IdempotencyService idem;
    private final SessionService sessions;
    private final Clock clock;

    public KnowledgeArticleServiceImpl(KnowledgeArticleMapper articles, UserMapper users, IdempotencyService idem,
        SessionService sessions, Clock clock) {
        this.articles = articles; this.users = users; this.idem = idem; this.sessions = sessions; this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeArticlePageVO list(AuthContext context, String view, String page, String q, String category) {
        view = view == null ? "PUBLISHED" : view;
        if (!Set.of("PUBLISHED", "MINE", "ALL").contains(view)) throw invalidQuery();
        if ("MINE".equals(view)) requireRole(context.user(), "ENGINEER");
        if ("ALL".equals(view)) requireRole(context.user(), "ADMIN");
        int pageNumber;
        try { pageNumber = page == null ? 1 : Integer.parseInt(page); }
        catch (NumberFormatException ex) { throw invalidQuery(); }
        if (pageNumber < 1) throw invalidQuery();
        if (q != null) {
            q = q.strip();
            if (q.isEmpty() || q.codePointCount(0, q.length()) > 100) throw invalidQuery();
        }
        if (category != null) { category = category.strip(); if (category.isEmpty()) throw invalidQuery(); }
        var result = articles.findPage(context.user().id, view, pageNumber, q, category);
        return new KnowledgeArticlePageVO(views(result.getRecords()), pageNumber, 20, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeArticleVO detail(AuthContext context, UUID id) {
        var article = articles.selectById(id);
        requireVisible(context.user(), article);
        return views(List.of(article)).getFirst();
    }

    @Override
    @Transactional
    public ActionVO create(AuthContext context, SaveKnowledgeArticleRequest body, String key) {
        validate(body);
        UUID requestKey = idem.key(key), actorId = context.user().id;
        String operation = "POST /api/v1/knowledge-articles", hash = idem.hash(body, null);
        idem.reserve(actorId, operation, requestKey);
        var actor = lockActor(context);
        requireRole(actor, "ENGINEER");
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        var article = new KnowledgeArticleEntity();
        article.id = UUID.randomUUID(); article.authorId = actorId; article.status = "DRAFT";
        article.title = body.title().strip(); article.content = body.content().strip(); article.category = body.category().strip();
        article.createdAt = clock.instant(); article.updatedAt = article.createdAt;
        articles.insert(article);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, article.id, article.version, 201);
    }

    @Override
    @Transactional
    public ActionVO change(AuthContext context, UUID id, String action, SaveKnowledgeArticleRequest body, String version, String key) {
        long expectedVersion = Values.version(version);
        if ("edit".equals(action)) validate(body);
        UUID requestKey = idem.key(key), actorId = context.user().id;
        String operation = "edit".equals(action) ? "PATCH /api/v1/knowledge-articles/" + id
            : "POST /api/v1/knowledge-articles/" + id + "/" + action;
        String hash = idem.hash(body, version);
        idem.reserve(actorId, operation, requestKey);
        var actor = lockActor(context);
        if (!"edit".equals(action)) requireRole(actor, "ADMIN");
        var article = articles.lock(id);
        requireVisible(actor, article);
        if ("edit".equals(action) && (!"ENGINEER".equals(actor.role) || !actorId.equals(article.authorId)))
            throw forbidden();
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        if (article.version != expectedVersion) throw new BusinessException(409, "VERSION_CONFLICT", "知识已更新，请刷新后重新确认");
        String expectedState = "unpublish".equals(action) ? "PUBLISHED" : "DRAFT";
        if (!expectedState.equals(article.status)) throw new BusinessException(409, "INVALID_STATE", "当前知识状态不允许此操作");
        switch (action) {
            case "edit" -> { article.title = body.title().strip(); article.content = body.content().strip(); article.category = body.category().strip(); }
            case "publish" -> { article.status = "PUBLISHED"; article.publishedAt = clock.instant(); }
            case "unpublish" -> { article.status = "DRAFT"; article.publishedAt = null; }
            default -> throw new IllegalArgumentException("Unknown knowledge action");
        }
        article.version++; article.updatedAt = clock.instant();
        articles.updateById(article);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, id, article.version, 200);
    }

    private UserEntity lockActor(AuthContext context) {
        var actor = users.lock(context.user().id);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        return actor;
    }

    private void requireRole(UserEntity actor, String role) { if (!role.equals(actor.role)) throw forbidden(); }
    private BusinessException forbidden() { return new BusinessException(403, "FORBIDDEN", "无操作权限"); }
    private BusinessException invalidQuery() { return new BusinessException(400, "INVALID_QUERY", "查询参数无效"); }
    private void requireVisible(UserEntity actor, KnowledgeArticleEntity article) {
        if (article == null || (!"PUBLISHED".equals(article.status) && !"ADMIN".equals(actor.role)
            && !("ENGINEER".equals(actor.role) && actor.id.equals(article.authorId))))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "知识不存在或不可访问");
    }
    private void validate(SaveKnowledgeArticleRequest body) {
        if (body == null || body.title() == null || body.title().isBlank()) throw BusinessException.field("title", "请填写标题");
        if (body.content() == null || body.content().isBlank()) throw BusinessException.field("content", "请填写正文");
        if (body.category() == null || body.category().isBlank()) throw BusinessException.field("category", "请填写分类");
    }
    private List<KnowledgeArticleVO> views(List<KnowledgeArticleEntity> records) {
        if (records.isEmpty()) return List.of();
        var ids = records.stream().map(article -> article.authorId).collect(Collectors.toSet());
        var names = users.selectList(new QueryWrapper<UserEntity>().in("id", ids)).stream()
            .collect(Collectors.toMap(user -> user.id, user -> user.name));
        return records.stream().map(article -> new KnowledgeArticleVO(article.id, article.title, article.content,
            article.category, article.status, article.authorId, names.get(article.authorId), article.version,
            article.createdAt, article.updatedAt, article.publishedAt)).toList();
    }
}
