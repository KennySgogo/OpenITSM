# OpenITSM API

面向前端的接口文档。所有接口按模块统一格式列出；标题标注（已实现）的规格以 `source/backend` 当前代码为准，与 PRD 冲突处已在文中标注；其余为设计稿，后端未实现，字段以实现为准。

- 后端基础地址（本地）：`http://127.0.0.1:8080`
- 接口前缀：`/api/v1`（健康检查除外）
- 当前已实现：认证、成员管理、健康检查（M01 后端）、内部提单（M02 前后端）、工单流转（M03 前后端）、列表详情与检索（M05 前后端，暂不含 SLA）
- M04、M06 及之后模块：未实现（本文档含接口设计稿）

## 通用约定

### 会话与 Cookie

| 项 | 约定 |
| --- | --- |
| 会话载体 | HttpOnly Cookie，前端 JS 不可读写 |
| Cookie 属性 | SameSite=Lax，Path=/；生产环境 Secure |
| 请求携带 | 所有请求必须带 Cookie；本地跨端口（5173→8080）fetch 需 `credentials: "include"` |
| 匿名会话 | 调用 `GET /api/v1/auth/csrf` 自动建立，有效期 2 小时 |
| 登录会话 | 最长 24 小时（到 `absoluteExpiresAt`）；连续 2 小时无 activity 上报即失效 |
| 会话失效表现 | 需登录的接口统一返回 401 SESSION_EXPIRED |

会话失效触发条件（前端不区分原因，统一按 401 处理）：

| 触发条件 |
| --- |
| 24 小时绝对到期 |
| 连续 2 小时无 activity 上报 |
| 账号被停用、被改角色、密码被重置（该账号全部会话立即失效） |

匿名可直接访问的接口（其余 `/api/**` 均需登录）：

| 接口 |
| --- |
| GET /api/v1/auth/csrf |
| POST /api/v1/auth/login |
| POST /api/v1/auth/password-reset-requests |
| POST /api/v1/auth/password-resets |
| POST /api/v1/auth/activations |

### CSRF

| 项 | 约定 |
| --- | --- |
| 适用范围 | 除 GET / HEAD / OPTIONS 外的所有请求 |
| 必需请求头 | `Origin`（必须精确等于部署配置的源）和 `X-CSRF-Token`（64 字符） |
| 本地 Origin 值 | `http://localhost:5173` |
| token 来源 | GET /api/v1/auth/csrf、POST /api/v1/auth/login、GET /api/v1/auth/me 的响应 |
| 校验失败 | 403 CSRF_INVALID |
| 处理方式 | 重新 GET /api/v1/auth/csrf 取新 token 后重试一次原请求；再失败则整页刷新 |

token 与当前会话绑定：登录、重置密码等会换发会话，token 随之更换。

### 请求头

| Header | 必填场景 | 说明 |
| --- | --- | --- |
| Content-Type: application/json | 有请求正文的接口 | 正文必须是合法 JSON |
| Origin | 所有写请求 | 见 CSRF 一节 |
| X-CSRF-Token | 所有写请求 | 见 CSRF 一节 |
| If-Match | 成员管理及 M03 写操作 | `"<version>"`，如 `"3"`；见乐观版本与幂等键 |
| Idempotency-Key | 成员管理、M02 创建及 M03 写操作 | UUID；见乐观版本与幂等键 |
| X-Request-ID | 可选 | UUID；非法值被忽略 |

### 请求正文

| 项 | 约定 |
| --- | --- |
| 编码 | UTF-8 JSON |
| 未知字段 | 一律拒绝（400 VALIDATION_FAILED），字段名必须与本文档一致 |
| 大小上限 | 256 KiB，超出返回 413 REQUEST_TOO_LARGE |
| 可选字段 | 省略即可，不要显式传 null |

### 成功响应

统一结构（健康检查与 204 除外）：

```json
{
  "data": {},
  "requestId": "0b6f2a1e-5d3c-4f7e-9a2b-8c1d0e3f4a5b"
}
```

- `data`：各接口定义的数据对象，字段见各接口规格。
- 返回 204 的接口没有响应正文。

### 失败响应

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请输入有效邮箱",
    "fieldErrors": [
      {"field": "email", "code": "INVALID", "message": "请输入有效邮箱"}
    ],
    "retryable": false
  },
  "requestId": "0b6f2a1e-5d3c-4f7e-9a2b-8c1d0e3f4a5b"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| error.code | string | 错误码，见全局错误码表 |
| error.message | string | 中文提示，可直接展示给用户 |
| error.fieldErrors | array | 字段级错误；无字段错误时为空数组 |
| error.fieldErrors[].field | string | 出错字段名（请求体字段或请求头名称） |
| error.retryable | boolean | 429 与 503 为 true，其余为 false |

### 全局错误码

| HTTP | error.code | 触发条件 | 前端处理 |
| --- | --- | --- | --- |
| 400 | VALIDATION_FAILED | 参数缺失、格式错误、未知字段、JSON 不可解析 | 展示 fieldErrors；没有则展示 message |
| 401 | SESSION_EXPIRED | 未登录或会话失效 | 清空本地会话，跳登录页并携带 returnTo |
| 403 | CSRF_INVALID | 写请求缺 X-CSRF-Token、token 不匹配或 Origin 不符 | 见 CSRF 一节 |
| 403 | FORBIDDEN | 已登录但角色不足 | 提示无权限，不重试 |
| 404 | RESOURCE_NOT_FOUND | 路径不存在或目标资源不存在 | 提示不存在并返回列表 |
| 405 | METHOD_NOT_ALLOWED | HTTP 方法与接口不符 | 前端代码缺陷，修正请求方法 |
| 409 | REQUEST_IN_PROGRESS | 同一幂等键的请求正在处理 | 1 秒后用同一幂等键和同一正文重试 |
| 413 | REQUEST_TOO_LARGE | 请求正文超过 256 KiB | 前端代码缺陷 |
| 415 | MEDIA_TYPE_UNSUPPORTED | Content-Type 不是 JSON | 设置 application/json |
| 429 | RATE_LIMITED | 触发限流 | 按 Retry-After 等待后重试 |
| 500 | INTERNAL_ERROR | 服务内部错误 | 提示稍后重试，可附 requestId |
| 503 | DEPENDENCY_UNAVAILABLE | 数据库或邮件配置不可用 | 提示稍后重试；retryable=true |

### 乐观版本与幂等键

| 项 | 约定 |
| --- | --- |
| If-Match 格式 | 引号包裹的十进制数字，如 `"3"` |
| 版本来源 | 成员列表的 `version` 字段；M03 使用 M05 列表/详情返回的工单 `version` |
| 缺失 If-Match | 428 PRECONDITION_REQUIRED |
| 版本不匹配 | 409 VERSION_CONFLICT；重新拉取列表，由用户确认后重新提交 |
| Idempotency-Key | 每次新操作生成新 UUID；网络失败重试时复用同一 UUID 和同一正文 |
| 同键不同正文 | 409 IDEMPOTENCY_CONFLICT；换新键重新提交 |
| 重放成功 | 响应头 `Idempotent-Replayed: true` 且 `data.replayed` 为 true，按成功处理 |

### 限流

超限返回 429 RATE_LIMITED，响应头 Retry-After 为等待秒数。“来源”指请求方 IP。

| 接口 | 限额 |
| --- | --- |
| GET /api/v1/auth/csrf | 每来源 60 次 / 1 分钟 |
| POST /api/v1/auth/login | 每来源 60 次 / 1 分钟 |
| POST /api/v1/auth/activity | 同一会话 60 秒内 1 次 |
| POST /api/v1/auth/password-reset-requests | 每来源 60 次 / 1 分钟；同一邮箱 5 次 / 1 小时，两次至少间隔 60 秒 |
| POST /api/v1/auth/password-resets、POST /api/v1/auth/activations | 每来源 60 次 / 1 分钟 |
| POST /api/v1/users、POST /api/v1/users/{id}/invitation-resend | 同一邮箱 5 次 / 1 小时，两次至少间隔 60 秒 |

### 时间格式

| 项 | 约定 |
| --- | --- |
| 格式 | ISO-8601 UTC 字符串 |
| 示例 | `2026-10-15T08:30:00Z`、`2026-10-15T08:30:00.123Z` |
| 解析 | 交给 Date 构造器或时间库，勿手工截取字符串 |

### requestId

| 项 | 约定 |
| --- | --- |
| 来源 | 每个响应都有；响应头 X-Request-ID 与响应体 requestId 同值 |
| 请求传入 | 可选携带 X-Request-ID（UUID），非法值忽略并新生成 |
| 前端用途 | 用户反馈问题时附上 requestId，用于后端日志定位 |

### 公共枚举

| 枚举 | 取值 |
| --- | --- |
| role | EMPLOYEE、ENGINEER、ADMIN |
| 用户 status | INVITED（待激活）、ACTIVE（在职）、DISABLED（停用） |

## 接口

所有模块统一采用“接口表 + 参数要点”格式。所有接口沿用通用约定（认证、CSRF、统一响应结构、全局错误码）；工单与知识的写操作沿用 If-Match / Idempotency-Key 约定。M02 创建新工单仅需 Idempotency-Key，不要求 If-Match。

### M01 认证（已实现）

匿名可直接访问的接口见通用约定，其余需登录。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/auth/csrf | 获取 CSRF 令牌；未登录时同时建立匿名会话 | 匿名 |
| POST | /api/v1/auth/login | 邮箱密码登录，换取登录会话 | 匿名 |
| GET | /api/v1/auth/me | 应用加载时恢复登录态 | 已登录 |
| POST | /api/v1/auth/activity | 上报用户活跃，维持会话 | 已登录 |
| POST | /api/v1/auth/logout | 退出当前浏览器会话 | 已登录 |
| POST | /api/v1/auth/password-reset-requests | 申请找回密码 | 匿名 |
| POST | /api/v1/auth/password-resets | 重置密码 | 匿名（持有效 token） |
| POST | /api/v1/auth/activations | 激活账号 | 匿名（持有效 token） |

参数要点：

- csrf：返回 64 字符 `csrfToken`；匿名会话 2 小时有效，过期后再调用换发新 Cookie 和新 token；已登录时返回当前登录会话的 token，Cookie 不变。
- login：正文 email（去空格转小写，≤254 字符）、password（非空，≤64 码点）、可选 returnTo（站内路径，≤2048 字符）；成功换发会话与新 csrfToken，返回 user（含 id、name、email、role、status、version、lockedUntil）、defaultPath、redirectPath、absoluteExpiresAt、message。邮箱或密码错误、账号锁定（连续错 5 次锁 15 分钟）或已停用统一返回 401 AUTH_INVALID，前端不区分原因。
- defaultPath 按角色：ADMIN→/dashboard、ENGINEER→/tickets?view=ASSIGNED、EMPLOYEE→/tickets?view=MINE；工单列表已实现，看板仍为占位页。
- returnTo 校验（不通过则回退 defaultPath 并提示）：/knowledge、/tickets、/tickets/new、/tickets?view=MINE 全角色；/tickets?view=ASSIGNED、UNASSIGNED、ALL 非员工；/tickets/{id} 员工限本人工单，其他角色须工单存在；/dashboard、/admin/users、/settings 管理员。
- me：Body 与 login 成功响应相同（redirectPath 恒等于 defaultPath）；响应头 ETag 与 user.version 一致，用于比对用户信息是否变化；401 时跳登录页并携带 returnTo。
- activity：无正文，204；每 55～60 秒上报一次；同会话 60 秒内重复返回 429，忽略即可；只刷新无操作计时，不延长 absoluteExpiresAt；收到 401 停止上报并跳登录页。
- logout：无正文，204；成功后清空本地状态并跳登录页；同一账号其他设备的会话不受影响。
- password-reset-requests：正文 email；无论邮箱是否存在均返回 202 与统一文案，不得据此推断账号存在；邮件服务未配置返回通用 503。
- password-resets：正文 token（43 字符，申请后 30 分钟有效，一次性）、password（12～64 字符，至少一个字母和一个数字）、confirmPassword（须与 password 一致）；成功后该账号全部会话失效，必须重新登录；token 不存在、已使用或已过期返回 409 TOKEN_INVALID。
- activations：参数与密码规则同重置密码；邀请链接发出后 48 小时有效，重发邀请后旧链接失效；账号不处于待激活状态返回 409 TOKEN_INVALID。

### M01 成员管理（已实现）

除 assignees 外均要求管理员；assignees 要求工程师或管理员。角色不足返回通用 403 FORBIDDEN。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/users | 分页查询成员 | 管理员 |
| GET | /api/v1/users/assignees | 查询可分派工程师及当前负载 | 工程师、管理员 |
| POST | /api/v1/users | 邀请成员 | 管理员 |
| PATCH | /api/v1/users/{id} | 修改成员姓名或角色 | 管理员 |
| POST | /api/v1/users/{id}/disable | 停用成员 | 管理员 |
| POST | /api/v1/users/{id}/unlock | 解除登录锁定 | 管理员 |
| POST | /api/v1/users/{id}/invitation-resend | 重发邀请 | 管理员 |

参数要点：

- 查询：Query page（默认 1，须 ≥1）、q（去空格后 1～100 字符，同时模糊匹配姓名与邮箱，不区分大小写）、role、status；pageSize 固定 20，按更新时间倒序；page 或 status 非法返回 400 INVALID_QUERY。列表项含 version（写操作作 If-Match）与 lockedUntil（仅晚于当前时间时展示解锁入口；2026-10-15 新增，登录与 me 的 user 亦含此字段，不暴露失败次数）。
- assignees：返回在职工程师的 id、name、unresolvedCount、pendingConfirmationCount 与统计时点 asOf，按姓名排序；两个计数查询正式工单表；员工角色不展示分派入口。
- 写操作（邀请、修改、停用、解锁、重发）均需 If-Match 与 Idempotency-Key；成功返回 resourceId（目标成员 ID）、acceptedVersion（受理后版本）、replayed（true 表示幂等重放）。
- 邀请：正文 name（去空格后 1～80 字符）、email、role；成功 201，响应头 Location 为 `/api/v1/users?resourceId=<新成员ID>`；邮箱已存在返回 409 EMAIL_EXISTS；新成员状态为 INVITED，须完成激活才能登录。
- 修改：正文 name、role 至少提供一个，否则 400；修改角色后该成员全部会话立即失效；最后一名在职管理员不能改角色（409 LAST_ADMIN）；工程师降为员工且有未关闭工单返回 409 MEMBER_HAS_OPEN_TICKETS。
- 停用：正文 reason 必填（去空格后 5～500 字符，用于审计）；已停用返回 409 INVALID_STATE；最后一名在职管理员不能停用（409 LAST_ADMIN）；停用后该成员会话立即失效，邀请与重置链接全部作废。
- 解锁：无正文；锁定最长 15 分钟自动解除，本接口用于提前解锁；对未锁定的成员调用也返回成功（重置失败计数）。
- 重发邀请：无正文，成功 202；仅对 INVITED 成员可用，否则 409 INVALID_STATE；重发后旧邀请链接立即失效。
- 成员不存在时写操作返回 404 RESOURCE_NOT_FOUND。

### 健康检查（已实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /health/live | 存活探针，确认后端进程在运行 | 服务器本机 |
| GET | /health/ready | 就绪探针，确认数据库等依赖可用 | 服务器本机 |

参数要点：

- 不使用统一响应结构，成功 Body 为 `{"status": "UP"}`，直接读取顶层 status 字段。
- ready 在依赖不可用时返回 503，Body 为 `{"status": "DOWN"}`，不是统一错误结构。
- 仅允许服务器本机访问，浏览器调用返回通用 403 FORBIDDEN。

### M02 内部提单（后端已实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets | 创建工单，系统生成唯一编号 | 全角色 |

参数要点：

- 正文：`title`（标题）、`description`（描述）、`businessType`（业务类型）、`impactScope`（影响范围），均为必填字符串，去首尾空白后非空。业务类型和影响范围使用自由文本，不设枚举或额外字段长度上限，沿用 256 KiB 正文总大小限制。
- 已登录的员工、工程师、管理员均可创建。提单人取当前用户，初始状态为 NEW，无负责人；不接受客户端指定提单人、状态或负责人。
- 必须携带 UUID 格式的 Idempotency-Key，不要求 If-Match。作用域沿用现有实现：当前用户 + `POST /api/v1/tickets` + 幂等键；请求摘要沿用 DTO 序列化结果及空版本值，不因字段排列顺序改变而冲突，原始字段值不同（包括首尾空白不同）则不同，不新增记录有效期或清理规则。
- 成功返回 HTTP 201，统一 `data` 包含 `id`（UUID）、`number`（工单编号）、`replayed`（首次 false）。编号由 PostgreSQL 序列生成 `ITSM-数字`，唯一但不保证连续。
- 同键同正文返回首次工单的 ID/编号，仍为 HTTP 201，`data.replayed=true` 且响应头 `Idempotent-Replayed: true`；不同正文返回 409 IDEMPOTENCY_CONFLICT。同键正在处理返回 409 REQUEST_IN_PROGRESS，按通用约定重试。
- 字段缺失、空白或非字符串、未知字段、幂等键缺失或非法返回 400 VALIDATION_FAILED，未登录或会话失效返回 401 SESSION_EXPIRED，CSRF 错误返回 403 CSRF_INVALID；其余沿用通用错误响应。

### M03 工单处理（前后端已实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/claim | 认领 NEW 无主单，或受理分派给本人的 NEW 单，变为处理中 | 工程师 |
| POST | /api/v1/tickets/{id}/assignments | 分派工程师，状态不变 | 管理员 |
| POST | /api/v1/tickets/{id}/transfers | 转派给其他有效工程师 | 工程师（本人负责单）、管理员 |
| POST | /api/v1/tickets/{id}/suspensions | 挂起，处理中变为已挂起 | 工程师（本人负责单） |
| POST | /api/v1/tickets/{id}/resume | 恢复，已挂起变为处理中 | 工程师（本人负责单） |
| POST | /api/v1/tickets/{id}/resolutions | 解决，处理中变为已解决 | 工程师（本人负责单） |
| POST | /api/v1/tickets/{id}/close | 关闭，已解决变为已关闭 | 提单人；工程师、管理员代确认 |
| POST | /api/v1/tickets/{id}/reopen | 重开为处理中 | 提单人、负责工程师、管理员 |
| POST | /api/v1/tickets/{id}/priority-changes | 调整优先级 | 管理员 |

参数要点：

- 本节细则已由用户在 M03 开发前确认。九个接口均需 If-Match（当前工单 version、带引号数字）及 UUID Idempotency-Key；缺版本头 428 PRECONDITION_REQUIRED，格式错误 400 VALIDATION_FAILED，版本不匹配 409 VERSION_CONFLICT。工单版本通过 M05 列表/详情读取。
- 成功 HTTP 200，`data` 为 `resourceId`（工单 UUID）、`acceptedVersion`（受理后版本）、`replayed`；ETag 为受理版本。同键同正文及原版本重放仍返回首次结果，带 `Idempotent-Replayed: true`；同键异正文/异版本返回 409 IDEMPOTENCY_CONFLICT。同键进行中为 409 REQUEST_IN_PROGRESS。沿用当前用户 + HTTP 方法及完整路径 + key 作用域、DTO/空正文及版本头摘要，不改变有效期与存储规则。
- claim、resume 不接受正文。claim 仅限 NEW 且无负责人或已分派给当前工程师；认领/受理将负责人设为当前工程师。并发认领仅一个成功，其余用旧版本返回 409 VERSION_CONFLICT。
- assignments、transfers 正文为 `assigneeId`（UUID，必填），仅允许 NEW、IN_PROGRESS、ON_HOLD；目标须为 ACTIVE ENGINEER，转派须选择不同于当前负责人的工程师。分派/转派不改变状态或轮次起点。分派只限管理员；转派只限管理员或负责工程师。
- suspensions 正文为 `reason`，仅负责工程师可将 IN_PROGRESS 改为 ON_HOLD；resume 仅负责工程师可将 ON_HOLD 改为 IN_PROGRESS，均不改变轮次起点。
- resolutions 正文为 `summary`（公开解决摘要），仅负责工程师可将 IN_PROGRESS 改为 RESOLVED。摘要由 M05 的 resolutionSummary 返回，按工单可见性读取；不提供 M04 回复或操作记录查询接口。
- close 仅限 RESOLVED。提单人可无正文，也可提供 `reason`；工程师或管理员代确认必须提供 `reason`。成功改为 CLOSED 并记录关闭时间。
- reopen 正文为 `reason`，仅限提单人、负责工程师或管理员；RESOLVED 可重开，CLOSED 自关闭起 7×24 小时内（含等于 7 天）可重开，超出返回 409 INVALID_STATE。改为 IN_PROGRESS，保留优先级/最近解决摘要，清空关闭时间并更新当前轮次起点。原负责人仍为 ACTIVE ENGINEER 时保留；已停用或不再是工程师时清空负责人，由管理员后续分派（此边界已由用户补充确认）。
- priority-changes 正文为 `priority`（P1～P4）与 `reason`，仅管理员可操作；不改变状态或轮次起点。新旧工单初始优先级 P3。此阶段仅保存轮次起点，不实现 M07 时限、超时和达标计算。
- `reason`、`summary` 均须为字符串，提供时去首尾空白后非空，无额外字段长度限制，沿用 256 KiB 总大小；未知字段及非法参数为 400 VALIDATION_FAILED。错误沿用统一结构。
- 无权限为 403 FORBIDDEN；资源不存在或对员工不可见为 404 RESOURCE_NOT_FOUND；状态不允许、超过重开期限为 409 INVALID_STATE；目标非有效工程师或转派给当前负责人为 400 VALIDATION_FAILED。失败不更新工单、审计或成功幂等记录。
- 流转、原因审计和成功幂等记录在同一事务提交；沿用 M01 成员控制锁，再锁成员行、工单行，使分派/认领与成员停用/降级串行，避免产生无效负责人。
- 前端在详情页按角色、负责人及状态提供上述九个动作；分派/转派复用 GET /users/assignees。提交保留原始正文、版本和幂等键，结果不确定时锁定表单并重试原请求；版本/状态冲突展示后端错误并刷新详情，由用户重新确认，不自动以新版本提交。

### M04 回复与附件（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/replies | 发表公开回复 | 提单人、工程师、管理员 |
| GET | /api/v1/tickets/{id}/replies | 查看公开回复（按角色过滤内部备注） | 同工单详情可见范围 |
| POST | /api/v1/tickets/{id}/internal-notes | 添加仅工程师、管理员可见的内部备注 | 工程师、管理员 |
| POST | /api/v1/tickets/{id}/attachments | 上传附件 | 提单人、工程师、管理员 |
| GET | /api/v1/attachments/{attachmentId} | 鉴权下载附件 | 工单可见者；内部附件仅工程师、管理员 |
| GET | /api/v1/tickets/{id}/operations | 查看工单操作记录 | 同工单详情可见范围 |

参数要点：

- replies、internal-notes 正文含回复内容。
- 附件上传使用 multipart/form-data，是通用 JSON 约定的例外；上传时选择可见性（公开或内部）。
- 已关闭工单不能再追加回复与附件，引导用户新建单。

### M05 列表与检索（前后端已实现，暂不含 M07 SLA）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/tickets | 视图与组合筛选查询 | 按视图与角色 |
| GET | /api/v1/tickets/{id} | 查看工单详情 | 员工限本人；工程师、管理员全部 |

参数要点：

- 本节细则已由用户在 M05 开发前确认。Query `view` 默认 MINE，取值 MINE（本人提交）、ASSIGNED（本人负责）、UNASSIGNED（负责人为空）、ALL。MINE 全角色可用；其他视图仅工程师、管理员，员工访问返回 403 FORBIDDEN。
- Query `page` 默认 1，须为 ≥1 的整数，pageSize 固定 20；按 createdAt 倒序、id 倒序排列。成功 HTTP 200，data 为 items、page、pageSize、total。超出末页返回空 items，保留实际总数。
- 可选组合条件：`status`（NEW/IN_PROGRESS/ON_HOLD/RESOLVED/CLOSED）、`businessType`（去首尾空白后的非空字符串，精确匹配）、`q`（去首尾空白后 1～100 个 Unicode 码点）。q 不区分大小写，按编号、标题、描述包含匹配；百分号、下划线和反斜杠按字面字符处理。视图、筛选与关键词条件同时生效。
- 非法 view/page/status、空白 businessType 或无效 q 返回 400 INVALID_QUERY；省略可选筛选即可，不传空字符串。UUID 路径格式非法返回 400 VALIDATION_FAILED。不存在或员工无权查看的详情统一返回 404 RESOURCE_NOT_FOUND；未登录返回 401 SESSION_EXPIRED，沿用统一错误结构。
- 列表项与详情均返回：id、number、title、description、businessType、impactScope、requesterId、requesterName、assigneeId、assigneeName、status、priority、version、createdAt、resolutionSummary、closedAt、cycleStartedAt。未分派的 assigneeId/assigneeName 为 null；无解决摘要或关闭时间时相应字段为 null。不返回账号邮箱、密码、令牌或内部审计原因。
- 员工只可读取本人提交的工单；工程师、管理员可读取全部工单详情。解决摘要按工单可见性公开展示。GET 不修改工单、不创建审计或幂等记录，也不延长会话。
- 前端 `/tickets?view=…` 对接列表、筛选、搜索和分页，`/tickets/{id}` 展示真实详情及解决摘要并接入 M03 操作区；提单成功可进入详情。M04 回复/附件/操作记录未接入。
- 按用户确认，本阶段不返回或显示 SLA 字段，不使用假指标；待 M07 实现后补齐原设计中的 SLA 数据。

### M06 邮件通知（账号邮件后台投递已接入；工单通知未实现）

无新增前端接口。邀请、重发邀请、重置申请继续调用 M01 既有接口写入任务；后台在显式启用且配置就绪后投递和重试，接口成功仅表示已受理，不等于收件箱已收到。配置方式见 source/backend/README.md。

工单关键动作通知仍属后续范围。前端不调用投递接口；M14 的 GET /api/v1/settings 及邮件配置状态页面均尚未实现，不作为当前配置入口。

### M07 处理时限（未实现）

无独立接口。SLA 时限与超时标识随工单数据返回：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/tickets | 列表项含响应、解决时限的超时标识 | 同 M05 |
| GET | /api/v1/tickets/{id} | 详情含各时限的目标时间点与超时状态 | 同 M05 |

参数要点：

- 时限由优先级决定：P1 15 分钟 / 4 小时，P2 1 小时 / 8 小时，P3 4 小时 / 24 小时，P4 8 小时 / 72 小时。
- 从工单创建起算；挂起和转派不暂停计时。

### M08 文本知识库（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/knowledge-articles | 创建知识草稿 | 工程师 |
| PATCH | /api/v1/knowledge-articles/{id} | 编辑草稿 | 工程师（草稿作者） |
| POST | /api/v1/knowledge-articles/{id}/publish | 发布知识 | 管理员 |
| POST | /api/v1/knowledge-articles/{id}/unpublish | 下架知识 | 管理员 |
| GET | /api/v1/knowledge-articles | 按标题、正文、分类检索已发布知识 | 全角色 |
| GET | /api/v1/knowledge-articles/{id} | 查看知识详情 | 已发布对全角色；草稿限作者、管理员 |

参数要点：

- 创建与编辑正文含标题、正文、分类。
- 检索参数：关键词（匹配标题与正文）、分类、page 分页。
- 下架后该知识不再出现在检索结果。

### M09 AI 分类与打标（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/classification-suggestions | 生成分类、标签、业务类型、优先级建议 | 提单人 |
| POST | /api/v1/tickets/{id}/classifications | 人工确认后正式写入分类结果 | 提单人 |

参数要点：

- 建议接口返回的均为建议值，不直接生效。
- classifications 正文含确认后的分类、标签、业务类型、优先级，可在建议值基础上修改。
- AI 不可用或已关闭（M14）时不影响人工提单与处理。

### M10 处理人推荐（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/assignee-recommendations | 返回最多 3 名候选工程师及推荐依据 | 工程师、管理员 |

参数要点：

- 候选为有效工程师，推荐结合已解决单经验与当前负载。
- 推荐不自动分派；实际分派调用 M03 的 assignments。
- 负载数据同 GET /api/v1/users/assignees。

### M11 相似问题检索（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/similar-issues | 返回最多 5 条相似来源 | 工程师、管理员 |

参数要点：

- 结果来自已解决工单与已发布知识，仅含调用角色可见的内容。
- 每条结果标注真实来源（工单编号或知识 ID）。

### M12 AI 回复草稿（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/reply-drafts | 基于当前问题与相似检索材料生成回复草稿 | 工程师 |

参数要点：

- 草稿不自动发送；人工核对修改后通过 M04 的 replies 发表。
- AI 不可用时不影响人工直接回复。

### M13 基础看板（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/dashboard/metrics | 看板统计 | 管理员 |

参数要点：

- 统计内容：工单数量、分类分布、响应与解决时长、时限达标率、人员负载。

### M14 最小配置（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/settings | 读取团队配置与 AI 调用状态 | 管理员 |
| PATCH | /api/v1/settings | 更新团队配置 | 管理员 |

参数要点：

- 更新正文含团队名称、邮件连接、模型连接、AI 开关。
- GET 返回上述配置及只读的 AI 调用状态。

## 内部接口（Java → Python，仅 M09～M12 涉及）

以下接口由 Java 后端调用 Python AI 服务，浏览器不可直接访问；不适用本文档的统一响应结构与会话约定。设计稿，未实现，字段以实现为准。

| 方法 | 路径 | 用途 | 对应前端接口 |
| --- | --- | --- | --- |
| POST | /internal/v1/classification-suggestions | 分类、标签、业务类型、优先级建议 | M09 classification-suggestions |
| POST | /internal/v1/assignee-recommendations | 处理人候选推荐 | M10 assignee-recommendations |
| POST | /internal/v1/similar-issues | 相似问题检索 | M11 similar-issues |
| POST | /internal/v1/reply-drafts | 回复草稿生成 | M12 reply-drafts |

Python 侧只做 AI 计算，不访问数据库；输入材料（工单文本、候选工程师与负载数据、检索语料）由 Java 组装后传入。
