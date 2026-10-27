# OpenITSM AI service

Python 3.12.15、FastAPI、Pydantic、HTTPX。处理 M14 模型连接测试、M09 分类建议、M10 处理人推荐、M11 相似检索及 M12 回复草稿，不访问数据库、不自动写入工单、不向浏览器开放调用。

## 运行与测试

从 source/ai-service 使用现有虚拟环境：

```powershell
.venv/Scripts/python.exe -m unittest discover -s tests -v
.venv/Scripts/python.exe -m app
```

入口固定监听 127.0.0.1:8001，关闭代理头、访问日志、文档与 CORS；在解析正文前拒绝非 loopback 客户端。Java 仅调用同机地址，不需要共享内部凭据。不要把服务放到公开反向代理之后或更改为公开监听；跨机器部署需要另行设计隔离网络。

模型连接要求 HTTPS。只有本机模型接收器测试可设置 OPENITSM_AI_ALLOW_LOCAL_MODEL=true，Java 同时使用 local/test profile。HTTPX 不读取代理环境、校验证书且禁止重定向；不把根 .env 的数据库或邮件凭据导入 Python。

## 接口

- POST /internal/v1/model-connection-tests：仅 modelConnection（baseUrl、model、apiKey），固定无业务数据短文本；仅返回 status。
- POST /internal/v1/classification-suggestions：模型连接、公开工单问题、有效类型候选；返回 businessTypeId、reason、suggestedPriority。建议不自动生效。
- POST /internal/v1/assignee-recommendations：模型连接、公开问题及 Java 提供的候选技能/负载/同类型解决数量；返回 items，最多 3 名唯一候选及简短理由，不自动分派。具体字段见根 API.md。
- POST /internal/v1/similar-issues：模型连接、公开问题和 Java 召回的最多 10 个公开来源片段；返回最多 5 个唯一 sourceKey 和理由。Python 不访问数据库、不生成链接或摘要；Java 复核来源并组装真实元数据。

- POST /internal/v1/reply-drafts：Java 提供当前公开问题和最多 5 个授权来源，返回待人工核对的纯文本 content，不发送回复或访问数据库。

模型 API 密钥仅用于供应商 Authorization，不进入提示词、日志和响应。输入验证错误不返回原始值。

模型使用普通 Chat Completions，不要求先测试连接，不附加 JSON Schema 或工具调用参数。提示词要求仅返回 JSON，Pydantic 动态候选类型校验实际结果，拒绝额外/缺失字段、重复键、无效候选、截断和拒答；无输出修复、协议探测或自动重试。管理员连接测试只检查一次固定短文本的正常响应。

错误区分 AI_PROVIDER_AUTH_FAILED、AI_PROVIDER_RATE_LIMITED、AI_PROVIDER_REQUEST_REJECTED、AI_TIMEOUT、AI_INVALID_RESPONSE 和 DEPENDENCY_UNAVAILABLE；日志只记录类别及供应商 HTTP 状态，不记录凭据、原始响应或工单材料。

Java 请求最多 128 KiB，完整模型请求最多 256 KiB，模型响应最多 64 KiB；输出预算 1024 tokens。HTTPX 连接/读/写/连接池超时为 2/5/3/2 秒。超限明确返回错误，不截断业务候选。
