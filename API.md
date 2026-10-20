# OpenITSM API

面向前端的接口文档。各模块先列现行规格或既有设计，再列“拟修改契约、功能链路”。现行规格以 `source/backend` 为依据；拟修改契约承载本轮已确认目标，均未因此实现，后续开发须遵循并验证。当前实现与目标的差异在对应模块和 STATUS 中标明，不以旧实现自动覆盖新需求。

- 后端基础地址（本地）：`http://127.0.0.1:8080`
- 接口前缀：`/api/v1`（健康检查除外）
- 当前已实现：认证、成员管理、健康检查（M01 后端）、内部提单（M02 前后端）、工单流转（M03 前后端）、回复/内部备注/附件/操作记录（M04 前后端）、列表详情与检索（M05 前后端）、处理时限（M07 前后端）
- M06 已接入账号邮件与工单通知的后台投递；M08 已实现，M09～M14 仍为设计稿。

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
| If-Match | 成员管理、M03 及 M04 回复/备注/附件写操作 | `"<version>"`，如 `"3"`；见乐观版本与幂等键 |
| Idempotency-Key | 成员管理、M02 创建、M03 及 M04 回复/备注/附件写操作 | UUID；见乐观版本与幂等键 |
| X-Request-ID | 可选 | UUID；非法值被忽略 |

### 请求正文

| 项 | 约定 |
| --- | --- |
| 编码 | UTF-8 JSON |
| 未知字段 | 一律拒绝（400 VALIDATION_FAILED），字段名必须与本文档一致 |
| 大小上限 | 256 KiB，超出返回 413 REQUEST_TOO_LARGE |
| 可选字段 | 默认省略，只有模块明确允许时可传 null |

### 成功响应

统一结构（健康检查、附件二进制下载与 204 除外）：

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

所有模块逐步统一为“接口表 + 参数要点 + 拟修改契约 + 功能链路”，后两项放在模块末尾，明确区分已实现与未实现。所有接口沿用通用约定（认证、CSRF、统一响应结构、全局错误码）；版本与幂等要求以各接口约定为准，新建资源不默认要求 If-Match。

### M01 认证（既有能力已实现，本人维护扩展未实现）

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

拟修改契约：

- 已确认增加本人改密及资料维护，以下接口均为拟新增、尚未实现；登录、邀请激活及邮件找回原契约继续适用。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/password-changes | 校验旧密码并修改本人密码 | 全部登录角色 |
| GET | /api/v1/profile | 读取本人姓名与技能描述 | 全部登录角色 |
| PATCH | /api/v1/profile | 修改本人姓名与技能描述 | 全部登录角色 |

- password-changes 正文拟为 oldPassword、password、confirmPassword；只允许当前会话对应的本人，不接收 userId。新密码沿用 12～64 字符、至少字母和数字、二次输入一致的规则；旧密码错误或字段非法拟返回 400 VALIDATION_FAILED 并定位字段，会话失效仍为 401 SESSION_EXPIRED。使用现有 PasswordEncoder 校验/编码，校验失败不变更密码或会话；成功拟返回 204、清除当前 Cookie，并使本人全部会话失效。沿用改密对旧重置链接的失效处理；不在日志、审计或幂等记录中保存密码正文。
- 改密使用会话与 CSRF，不要求 If-Match 或 Idempotency-Key；凭据校验与更新在成员锁内完成，避免与重置密码并发覆盖。成功后旧会话不可重放；响应丢失时先用新密码登录确认，不盲目自动重试原改密。拟复用现有限流机制，独立按成员 5 次/15 分钟及来源 60 次/分钟，超限沿用 429/Retry-After，不把旧密码输错累计为登录失败。
- GET profile 拟返回 data 的 id、name、skillsDescription、version 及 ETag，不返回他人资料。PATCH 至少提供 name 或 skillsDescription，均为字符串：姓名去首尾空白后 1～80 字符，技能描述按纯文本、空串表示清空，整体沿用 256 KiB 正文限制；未知字段及试图变更 email/role/status/userId 均拒绝。需要 If-Match 与 Idempotency-Key，版本沿用成员 version，与管理员修改同一成员并发时返回既有版本冲突。
- 资料更新拟沿用 resourceId/acceptedVersion/replayed 与 ETag，更新后前端重新读取本人资料和会话展示；姓名或技能改变不增加 authVersion、不撤销会话。技能仅供 M10 在候选合法的前提下作为补充材料，不向员工开放任意成员详情或原始技能文本读取接口。

功能链路：

- 登录：进入登录页 → 获取 CSRF → 输入邮箱密码 → 校验凭据和账号状态 → 换发会话 → 返回有权限的原页面或角色默认页；失败显示统一错误，保留重试入口。
- 邀请激活：打开有效邀请链接 → 设置并确认密码 → 校验链接和密码 → 激活账号 → 返回登录页 → 用户自行登录；无效链接提示重新联系管理员。
- 找回密码：填写邮箱 → 统一返回受理提示 → 有效账号按邮件流程获取链接 → 设置新密码 → 校验一次性链接 → 更新密码并使旧会话失效 → 重新登录。
- 会话与退出：应用加载查询 me → 有效则恢复登录态并按真实操作上报 activity → 失效则返回登录页；主动退出仅结束当前会话。
- 本人改密（已确认，尚未实现）：登录 → 打开修改密码入口 → 输入旧密码、新密码和确认密码 → 校验当前会话、CSRF、旧密码及新密码规则 → 同事务更新密码并撤销全部会话 → 返回登录页 → 用新密码登录；校验失败不改密码，响应未知时先登录确认。
- 本人资料（已确认，尚未实现）：登录 → 读取本人资料及版本 → 修改姓名或技能描述 → 校验字段、本人身份、版本及幂等 → 保存 → 刷新资料与姓名展示，继续当前会话；冲突后刷新并由用户确认，不覆盖管理员刚修改的资料。

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
- 写操作均需 Idempotency-Key；修改、停用、解锁、重发另需 If-Match，邀请新成员不要求 If-Match。成功返回 resourceId（目标成员 ID）、acceptedVersion（受理后版本）、replayed（true 表示幂等重放）。
- 邀请：正文 name（去空格后 1～80 字符）、email、role；成功 201，响应头 Location 为 `/api/v1/users?resourceId=<新成员ID>`；邮箱已存在返回 409 EMAIL_EXISTS；新成员状态为 INVITED，须完成激活才能登录。
- 修改：正文 name、role 至少提供一个，否则 400；修改角色后该成员全部会话立即失效；最后一名在职管理员不能改角色（409 LAST_ADMIN）；工程师降为员工且有未关闭工单返回 409 MEMBER_HAS_OPEN_TICKETS。
- 停用：正文 reason 必填（去空格后 5～500 字符，用于审计）；已停用返回 409 INVALID_STATE；最后一名在职管理员不能停用（409 LAST_ADMIN）；停用后该成员会话立即失效，邀请与重置链接全部作废。
- 解锁：无正文；锁定最长 15 分钟自动解除，本接口用于提前解锁；对未锁定的成员调用也返回成功（重置失败计数）。
- 重发邀请：无正文，成功 202；仅对 INVITED 成员可用，否则 409 INVALID_STATE；重发后旧邀请链接立即失效。
- 成员不存在时写操作返回 404 RESOURCE_NOT_FOUND。

拟修改契约：

- 配合已确认的 M02/M03，将 assignees 读取权限扩展至全角色、候选扩展至 ACTIVE ENGINEER/ADMIN；尚未实现，须与管理员接单/处理权限同步上线，不同时开放成员管理权限。
- 已确认工程师与管理员互转保留工单归属；工程师或管理员降为员工或停用时，仍有未结束负责单则返回 MEMBER_HAS_OPEN_TICKETS，先交接再操作，结束范围排除 CLOSED/REJECTED。角色变更后的会话失效及最后管理员保护继续适用。

功能链路：

- 邀请：管理员填写姓名、邮箱、角色 → 提交邀请 → 校验唯一性与幂等 → 创建待激活成员并入队邮件 → 返回受理结果 → 成员打开链接激活；受理不代表邮件送达。
- 修改或停用：管理员查询成员并取得版本 → 修改资料/角色或填写停用原因 → 校验权限、版本、最后管理员及未结工单保护 → 保存并执行必要的会话失效；冲突刷新后由用户确认重试。
- 解锁：管理员选择被锁成员 → 携带版本与幂等键提交 → 清除锁定 → 成员重新登录。
- 重发邀请：管理员选择待激活成员 → 校验版本与状态 → 使旧链接失效并入队新邮件 → 返回受理结果。
- 候选读取：具备读取权限的用户请求候选 → 服务端筛选有效工程师并统计负载 → 展示单选列表；创建或分派时仍须重新校验目标。

### 健康检查（已实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /health/live | 存活探针，确认后端进程在运行 | 服务器本机 |
| GET | /health/ready | 就绪探针，确认数据库等依赖可用 | 服务器本机 |

参数要点：

- 不使用统一响应结构，成功 Body 为 `{"status": "UP"}`，直接读取顶层 status 字段。
- ready 在依赖不可用时返回 503，Body 为 `{"status": "DOWN"}`，不是统一错误结构。
- 仅允许服务器本机访问，浏览器调用返回通用 403 FORBIDDEN。

### M02 内部提单（原四字段前后端已实现，扩展未实现）

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

拟修改契约（以下均未实现；负责人及分派通知、初始优先级、提单附件和统一业务类型规则已确认；新增类型接口为据此拟定的契约）：

- 创建正文增加可选 `assigneeId`（单个 UUID 字符串；省略或 null 表示不指定，空串、数组、非法 UUID 返回 400 VALIDATION_FAILED）。全角色均可提供，目标为 ACTIVE ENGINEER/ADMIN，工程师和管理员可选本人；管理员目标与 M03 处理权限同步开放。当前 DTO 尚无此字段，提交仍按未知字段返回 400。
- 复用 `GET /api/v1/users/assignees`，拟将调用权限扩展到全部登录角色，保持现有 items、asOf 及候选项字段和排序，不开放成员管理接口或其他人的工单读取权限。当前员工调用仍为 403、候选仍仅 ACTIVE ENGINEER；M01 节描述的是现状。前端仅单选，不做多选复制建单；候选加载失败或为空时可不指定提交，已选目标失效则提示重选，不静默清空后提交。
- 创建与初次指定一次提交：保持 NEW 和初始 version=0，提单人 MINE 与负责人 ASSIGNED 均可见，UNASSIGNED 不再包含该单；成功响应仍为 201 和 id/number/replayed。负责人通过既有详情 GET 取得最新版本，再调用 M03 claim（If-Match + Idempotency-Key）进入 IN_PROGRESS。创建不要求 If-Match，不由前端创建后再补一次 assignments，也不授予员工 M03 分派权限。
- 目标不存在、INVITED、DISABLED 或不具备 ENGINEER/ADMIN 角色时，400 VALIDATION_FAILED（fieldErrors 指向 assigneeId），整个事务回滚。复用分派的成员控制锁和有效性检查；候选查询结果不作为提交时的有效性保证。创建、初次分派审计 TICKET_ASSIGNMENTS、成功幂等结果及存在收件人时的分派通知入队须同事务完成；入队失败返回既有 503 并整体回滚。无负责人创建继续不写工单审计、不通知。
- 幂等作用域、响应及冲突码不变，摘要包含新增 DTO 字段；省略与 null 均映射为无负责人，相同键改为不同负责人返回 409 IDEMPOTENCY_CONFLICT。重放先返回原创建结果，不重新指定、不重发通知，也不因原负责人后来停用而重复建单；客户端对未知结果保留原字段及键。升级须兼容历史四字段请求与已保存摘要，不得仅因新增字段或默认值使旧请求重放冲突；类型契约调整也须覆盖历史重放。
- 通知规则已确认：复用 assignments 收件人规则；提单人即操作者，排除后仅通知新负责人，自提自接不创建通知任务。不新增直接发送入口，不在创建事务中调用 SMTP。投递开关、发送前权限复核及失败重试沿用 M06；201 仅确认工单及必要通知任务提交，不代表邮件送达。
- 创建正文拟增加 `priority`（P1～P4，省略默认 P3），全角色可提供；非法值返回 400 VALIDATION_FAILED，fieldErrors 指向 priority。初始值与工单一同保存，不调用仅管理员可用的 priority-changes；创建后仍按 M03 权限调整。当前 DTO 不支持 priority，创建固定 P3。现有 M07 若仍启用，应按所选初始优先级计算，M07/M13 后续简化口径单独确认。
- 业务类型拟使用稳定 UUID 标识 `businessTypeId`，新创建请求选择该标识，不再以自由文本 businessType 创建新类型。全部提单角色可读取可选项；工程师、管理员可创建，工程师仅修改或删除本人创建项，管理员可管理全部，员工不得管理。删除表示停止新提单选择，保留历史工单关联及统计；改名不变更 ID，历史工单和统计统一显示新名称。创建须校验选项存在且可用，失效返回 400 VALIDATION_FAILED（fieldErrors 指向 businessTypeId），提示重选、不静默替换；已成功请求先按幂等规则重放。升级保留旧四字段成功请求的识别和重放，不因类型映射或改名重建工单；当前仍仅支持自由文本，不能将此段视作可调用接口。
- 提单页选择文件后，先调用创建接口取得工单 ID，再读取详情版本，复用 M04 `POST /api/v1/tickets/{id}/attachments` 逐个上传；不改变创建接口的 JSON 格式，也不把附件纳入创建事务或创建幂等摘要。文件、可见性、大小及状态限制均沿用 M04，每次上传单独使用 If-Match 与 Idempotency-Key，并更新版本。工单成功但附件失败时分别展示结果，只重试未成功附件；未知结果保留该文件的原始版本和幂等键重放，明确版本冲突按 M04 刷新并由用户确认后重试，禁止改键盲重传或重新建单。未取得确定的建单结果时不得开始上传；不新增服务器附件暂存接口。

已确认保持的边界：description 仍为纯文本，不增加富文本、正文内嵌图片或隐私字段；截图走上述附件链路。M05 工单可见范围及 M04 内部内容权限保持现有规则。

本版取消标签，不增加标签字段；已确认增加建单前 AI 推荐，由 M10 提供不依赖工单 ID 的建议接口，用户确认后仍通过创建请求的 assigneeId 指定负责人，不自动建单或分派。

业务类型管理拟新增接口（未实现）：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/ticket-types | 读取可选类型；view=MANAGE 时读取管理列表 | 默认全角色；MANAGE 仅工程师、管理员 |
| POST | /api/v1/ticket-types | 创建类型 | 工程师、管理员 |
| PATCH | /api/v1/ticket-types/{id} | 修改类型名称 | 创建该类型的工程师、管理员 |
| DELETE | /api/v1/ticket-types/{id} | 从新提单选项移除，保留历史关联 | 创建该类型的工程师、管理员 |

- GET 默认返回可选项；MANAGE 返回可选类型及当前调用者的管理权限标记，不将列表可见性等同于修改权限。统一 data.items 含 id、name；管理列表另含 version、canManage，供版本校验及操作按钮使用，不返回成员隐私信息。
- 创建与修改正文均为 `name`，去首尾空白后必填；创建者由会话确定，初始迁入项归管理员管理，不伪造某个工程师为创建者。创建需 Idempotency-Key，修改和删除同时需 If-Match 与 Idempotency-Key；版本来自管理列表。创建成功拟返回 201、data 的 id/name/version/replayed；修改和删除沿用 resourceId/acceptedVersion/replayed 与 ETag。越权返回 403 FORBIDDEN，不存在或已移除目标返回 404 RESOURCE_NOT_FOUND；字段、版本、幂等冲突沿用通用约定，已成功原请求可重放。
- 改名和移除须与工单创建的类型校验并发协调，不能因先查到选项就跳过提交校验；工单查询与后续 M13 按类型 ID 关联现名及聚合。M05 的类型筛选、M09 的人工确认写入须同步采用该标识，具体关联契约在对应模块核对，不把旧自由文本筛选描述为已升级。

功能链路：

- 无负责人提单（已支持）：登录 → 进入提单页面 → 填写标题、描述、业务类型、影响范围 → 提交 `POST /api/v1/tickets` → 校验会话、CSRF、字段和幂等键 → 创建 NEW 工单并生成编号 → 展示成功回执 → 查看详情；工单进入提单人的“我的工单”和 IT 团队的“未分派队列”，后续由 M03 认领或分派。
- 失败修正（已支持）：提交 → 字段校验失败 → 不创建工单 → 显示错误并保留填写内容 → 修正后重新提交；会话失效则先重新登录。
- 结果不确定后重试（已支持）：提交后断网或未取得确定结果 → 保留原正文和幂等键 → 重试同一请求 → 首次未成功则创建，已成功则返回原工单 → 展示成功回执，不重复建单。
- 提单时指定负责人（已确认，尚未实现）：登录 → 进入提单页面 → 填写四项必填信息 → 查询候选处理人（工程师或管理员）并单选一人 → 随创建请求提交 assigneeId → 校验目标有效性及幂等 → 同一事务创建 NEW 工单并指定负责人 → 展示成功回执 → 工单进入提单人的“我的工单”和负责人的“我的待办” → 负责人查看详情并通过 M03 主动受理 → 进入处理中；不选负责人则走无负责人提单链路。
- 指定目标失效（尚未实现）：选择负责人 → 提交时发现目标不存在、已停用或不再具备处理角色 → 整单失败、不创建工单 → 显示负责人错误并保留表单 → 重新选择，或主动取消指定后重新提交。
- 初次分派通知（已确认，尚未实现）：提交时指定其他工程师 → 校验通过 → 同事务创建工单、记录初次分派并写通知任务 → 返回建单成功 → 后台按投递开关及权限发送 → 失败按 M06 重试；指定自己或不指定则不生成通知任务。入队失败则创建事务整体回滚，邮件投递失败不撤销已创建工单；附件上传失败也不重新创建分派通知。
- 统一类型及初始优先级（已确认，尚未实现）：进入提单页 → 加载可用业务类型 → 填写标题、描述、影响范围并选择类型 → 选择 P1～P4（默认 P3）及可选负责人 → 提交并校验类型、优先级和负责人 → 创建 NEW 工单 → 展示编号及详情入口；类型已删除则保留表单提示重选，不创建工单。
- 提单附件（已确认，尚未实现）：提单页选择文件 → 校验文件并保留在当前页面 → 确认工单创建成功 → 获取工单版本 → 逐个上传到该工单 → 展示各文件结果；失败或结果不确定 → 保留工单编号、文件与请求信息 → 按 M04 规则重试附件 → 不重复建单、不重复上传已成功文件。
- 类型维护（已确认，尚未实现）：工程师或管理员进入独立“业务类型”管理页（提单页提供跳转入口） → 创建类型，或选择有权管理的类型修改、删除 → 服务端校验角色、创建者权限和版本 → 创建后供全团队选择；改名后历史工单与统计统一显示新名，仍按同一类型统计；删除后从新提单选项移除，历史工单及统计保留；工程师操作他人创建项则拒绝。
- 类型初始化（已确认，尚未实现）：读取已有工单类型 → 相同文本去重建立类型及历史工单关联 → 初始类型由管理员管理 → 全团队从统一选项提单；没有旧数据 → 工程师或管理员先创建类型 → 再供提单选择，不自动填入未经确认的业务分类。

- 建单前推荐（已确认，尚未实现）：填写标题、描述、业务类型和影响范围 → 手动请求 M10 推荐 → 展示最多 3 名有效处理人及简短理由 → 提单人确认一人 → 创建时再次校验负责人有效性 → 按既有建单链路提交；失败或无候选时仍可人工选择或不指定提交，不展示历史工单正文。

### M03 工单处理（原九动作已实现，扩展未实现）

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
- resolutions 正文为 `summary`（公开解决摘要），仅负责工程师可将 IN_PROGRESS 改为 RESOLVED。摘要由 M05 的 resolutionSummary 返回，按工单可见性读取；回复和操作记录读取使用 M04 既有接口。
- close 仅限 RESOLVED。提单人可无正文，也可提供 `reason`；工程师或管理员代确认必须提供 `reason`。成功改为 CLOSED 并记录关闭时间。
- reopen 正文为 `reason`，仅限提单人、负责工程师或管理员；RESOLVED 可重开，CLOSED 自关闭起 7×24 小时内（含等于 7 天）可重开，超出返回 409 INVALID_STATE。改为 IN_PROGRESS，保留优先级/最近解决摘要，清空关闭时间并更新当前轮次起点。原负责人仍为 ACTIVE ENGINEER 时保留；已停用或不再是工程师时清空负责人，由管理员后续分派（此边界已由用户补充确认）。
- priority-changes 正文为 `priority`（P1～P4）与 `reason`，仅管理员可操作；不改变状态或轮次起点。新旧工单初始优先级 P3。M07 复用轮次起点计算时限、超时及达标状态。
- `reason`、`summary` 均须为字符串，提供时去首尾空白后非空，无额外字段长度限制，沿用 256 KiB 总大小；未知字段及非法参数为 400 VALIDATION_FAILED。错误沿用统一结构。
- 无权限为 403 FORBIDDEN；资源不存在或对员工不可见为 404 RESOURCE_NOT_FOUND；状态不允许、超过重开期限为 409 INVALID_STATE；目标非有效工程师或转派给当前负责人为 400 VALIDATION_FAILED。失败不更新工单、审计或成功幂等记录。
- 流转、原因审计和成功幂等记录在同一事务提交；沿用 M01 成员控制锁，再锁成员行、工单行，使分派/认领与成员停用/降级串行，避免产生无效负责人。
- 前端在详情页按角色、负责人及状态提供上述九个动作；分派/转派复用 GET /users/assignees。提交保留原始正文、版本和幂等键，结果不确定时锁定表单并重试原请求；版本/状态冲突展示后端错误并刷新详情，由用户重新确认，不自动以新版本提交。

拟修改契约（已确认方向，尚未实现）：

- 上述参数要点描述现行实现；目标将处理角色统一为 ENGINEER/ADMIN，候选、创建初次指定、assignments、transfers 和重开时负责人有效性统一要求 ACTIVE 且属于这两种角色。M10 推荐使用同一候选集合，旧接口路径及版本、幂等方式不变。
- claim 对工程师和管理员均仅允许 NEW 且无主或本人负责，受理后设本人为负责人并进入 IN_PROGRESS；不能借认领抢走他人已指定单。挂起、恢复、解决要求“当前负责人且为处理角色”，管理员也不例外；管理员接管他人单先调用 transfers 将负责人改为自己，再按当前状态处理。
- 成员工程师/管理员互转保留已有负责人归属；降为 EMPLOYEE 或停用前，任意未结束负责单均要求先交接，沿用 MEMBER_HAS_OPEN_TICKETS，最后管理员与会话撤销保护不变；结束范围见下条 REJECTED 约定。
- 拟新增 `POST /api/v1/tickets/{id}/rejections`，正文 reason 为必填非空字符串、去首尾空白，沿用总正文大小限制；仅当前负责的 ACTIVE ENGINEER/ADMIN 可将 NEW、IN_PROGRESS、ON_HOLD 改为 REJECTED。需要 If-Match 与 Idempotency-Key，沿用九个流转接口的成功响应及版本/幂等错误；目标工单不可见为 404、无操作权 403、状态不符 409、原因非法 400。拒绝、公开原因、拒绝时间、版本和 TICKET_REJECTIONS 审计同事务提交，不发送通知；成功重放不重复执行。
- REJECTED 为独立结束状态，保留负责人、rejectionReason、rejectedAt，不写 resolvedAt 或冒充 CLOSED。详情拟返回公开拒绝原因和拒绝时间；拒绝后禁止新回复、内部备注、附件上传和全部流转，仍按原权限查询/下载，不能重开，有需求须新建。成员交接与候选负载不计 REJECTED；RESOLVED 仍待确认，继续计入未结束交接范围。拒绝单独计数、不计平均解决耗时，统计日期范围见 M07/M13。
- 初始优先级由 M02 已确认的新契约选择 P1～P4，省略 P3；本节 priority-changes 继续仅管理员可用，不因提单开放优先级而放开后续调整。

功能链路：

- 认领/受理：处理人查看 NEW 无主单或本人待受理单 → 获取版本 → claim 校验身份、状态及版本 → 设本人负责并进入 IN_PROGRESS → 展示结果；当前仅工程师，拟同步开放管理员，并发旧版本只允许一个成功。
- 分派/转派：有权操作者选择有效目标 → 携版本及幂等键提交 → 校验权限、目标、状态和版本 → 同事务更新归属、审计及通知 → 状态保持不变；目标当前仅工程师，拟扩展管理员。
- 管理员接管（已确认，尚未实现）：查看他人负责单 → 先转派给自己 → 获取最新版本 → 按当前状态受理、挂起、恢复或解决；不直接越过归属校验处理他人单。
- 处理与解决：负责人在 IN_PROGRESS 填原因挂起 → ON_HOLD → 主动恢复 → IN_PROGRESS → 填写公开解决摘要 → RESOLVED；挂起和恢复不重置轮次。
- 确认关闭：提单人确认，或工程师/管理员填写代确认原因 → 校验 RESOLVED 和版本 → CLOSED → 保留鉴权读取，禁止追加回复与附件。
- 重开：有权用户填写原因 → 校验 RESOLVED 或 CLOSED 的七日期限 → 复核负责人有效性 → 重开 IN_PROGRESS 并重置本轮计时；负责人失效则清空，等待管理员分派。
- 冲突与重试：写请求结果未知 → 保留原正文、版本和幂等键重试 → 返回首次结果；明确版本/状态冲突 → 刷新详情并让用户确认，不自动覆盖。
- 拒绝（已确认，尚未实现）：当前负责人打开 NEW/IN_PROGRESS/ON_HOLD 工单 → 填公开拒绝原因 → 校验处理角色、本人负责、状态、版本与幂等 → 同事务进入 REJECTED、记录原因/时间及审计 → 页面只读展示，不发邮件；员工需继续报障时新建工单。

### M04 回复与附件（前后端已实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/replies | 发表公开回复 | 提单人、工程师、管理员 |
| GET | /api/v1/tickets/{id}/replies | 查看公开回复（按角色过滤内部备注） | 同工单详情可见范围 |
| POST | /api/v1/tickets/{id}/internal-notes | 添加仅工程师、管理员可见的内部备注 | 工程师、管理员 |
| POST | /api/v1/tickets/{id}/attachments | 上传附件 | 提单人、工程师、管理员 |
| GET | /api/v1/tickets/{id}/attachments | 分页读取可见附件 | 同工单详情可见范围 |
| GET | /api/v1/attachments/{attachmentId} | 鉴权下载附件 | 工单可见者；内部附件仅工程师、管理员 |
| GET | /api/v1/tickets/{id}/operations | 查看工单操作记录 | 同工单详情可见范围 |

参数要点：

- 本阶段细则已由用户确认。两个 POST 正文均为 `{"content":"回复内容"}`，content 必须为字符串，去首尾空白后非空，无额外字段长度限制；沿用 256 KiB JSON 总大小及未知字段拒绝规则。
- 写请求必须携带 If-Match 和 UUID Idempotency-Key。成功 HTTP 200，data 为 `resourceId`（工单 ID）、`acceptedVersion`（工单新版本）、`replayed`，ETag 为受理版本；回复或备注每次成功均递增工单 version，不改变状态、负责人或轮次起点。
- 沿用当前用户 + HTTP 方法及完整路径 + key 作用域、原始 DTO 及版本头摘要。同键同正文及原版本重放返回首次结果，带 `Idempotent-Replayed: true`，不重复写回复或审计；即使之后工单已关闭也可重放成功结果。同键异正文/版本返回 409 IDEMPOTENCY_CONFLICT，进行中返回 409 REQUEST_IN_PROGRESS。缺版本为 428 PRECONDITION_REQUIRED，非法版本/键为 400 VALIDATION_FAILED，旧版本为 409 VERSION_CONFLICT。
- NEW、IN_PROGRESS、ON_HOLD、RESOLVED 均允许追加；CLOSED 返回 409 INVALID_STATE，提示新建工单。公开回复允许提单人、工程师、管理员；工程师、管理员可在全部可见工单追加，不限负责人。员工仅能回复本人提单，不能添加内部备注（403 FORBIDDEN）。回复、工单版本、审计、成功幂等结果同事务提交，回复与关闭通过同一工单行锁串行。
- 两个 GET 均支持 Query `page`（默认 1，整数且 ≥1），固定 pageSize=20，按 createdAt、id 升序；data 为 `items`、`page`、`pageSize`、`total`。非法 page 为 400 INVALID_QUERY；超出末页返回空 items 与实际 total。GET 不更新工单、审计、幂等或会话活动时间。
- GET replies 合并公开回复与内部备注，每项为 `id`、`content`、`visibility`（PUBLIC/INTERNAL）、`authorId`、`authorName`、`createdAt`。员工只收到 PUBLIC 项，分页和 total 均先过滤；工程师、管理员可读取全部。姓名来自当前成员姓名。
- GET operations 返回已有 M03 流转及本阶段新增回复/备注动作，每项为 `id`、`action`、`actorId`、`actorName`、`createdAt`、`details`。action 沿用审计编码：TICKET_CLAIM、TICKET_ASSIGNMENTS、TICKET_TRANSFERS、TICKET_SUSPENSIONS、TICKET_RESUME、TICKET_RESOLUTIONS、TICKET_CLOSE、TICKET_REOPEN、TICKET_PRIORITY_CHANGES；新增 TICKET_REPLY、TICKET_INTERNAL_NOTE。不返回成员审计或未知类型审计。
- 员工 operations 在查询时排除 TICKET_INTERNAL_NOTE。回复/备注的 details 只含 replyId，不复制正文；流转的 details 含 before/after，字段只允许 status、assigneeId、priority、summary（公开解决摘要）、closedAt、cycleStartedAt。仅工程师、管理员在流转 details 中可另读 reason；员工不收到原因、内部审计字段或原始审计载荷。
- 不存在或员工不可见的工单统一 404 RESOURCE_NOT_FOUND；未登录/失效 401 SESSION_EXPIRED，CSRF 错误 403 CSRF_INVALID，其余沿用通用错误。首次响应及 SLA 由 M07 记录和计算，规则见下文。
- 详情页接入回复/操作记录独立分页、公开回复及员工不可选的内部备注。正文按纯文本展示，内部备注明确标识；已关闭单展示限制与新建入口。发表成功刷新详情版本及记录，并显示最新页；结果不确定时保留原正文/路径/版本/幂等键、锁定表单重试，版本或状态冲突保留错误及正文并刷新详情，由用户重新确认。与 M03 写操作互相禁用，避免同页并行提交。
- 附件细则已由用户确认。POST attachments 为 multipart/form-data，是 JSON 正文约定的例外；必须且仅提供一个文件 part `file` 和一个文本字段 `visibility`（PUBLIC/INTERNAL），重复/未知字段或多文件返回 400 VALIDATION_FAILED。单文件 1～10485760 字节（10 MiB，含上限），空文件 400、超限 413 REQUEST_TOO_LARGE；请求总大小额外允许 64 KiB 表单开销，其余 JSON 大小规则不变。不限制扩展名，不做预览或执行。
- 附件上传需要 If-Match、Idempotency-Key，成功 HTTP 200，返回现有工单 `resourceId`、`acceptedVersion`、`replayed` 及 ETag；递增工单 version，不改变状态/负责人/轮次。幂等作用域同回复，请求摘要含原始文件名、SHA-256 内容哈希、visibility 及原版本。同键原文件/名称/可见性/原版本重放不重复写文件，即使之后已关闭也返回首次结果；任一项改变为 409 IDEMPOTENCY_CONFLICT。同键正在处理、版本缺失/非法/冲突沿用回复错误规则。
- 上传状态与范围同公开回复；员工仅能给本人提单上传 PUBLIC，INTERNAL 为 403 FORBIDDEN。工程师、管理员可为全部可见工单上传公开或内部附件，不限负责人。CLOSED 禁止上传（409 INVALID_STATE），但保留已有附件下载能力。
- GET attachments 使用 page（默认1，≥1整数）、固定 pageSize=20、createdAt/id 升序，返回 items/page/pageSize/total；每项为 id、fileName、size（字节）、visibility、uploaderId、uploaderName、createdAt。员工的内部项在查询和计数前排除；不返回路径、哈希或账号凭据。非法 page 为 400 INVALID_QUERY，不存在/不可见工单为 404 RESOURCE_NOT_FOUND。
- 下载成功 HTTP 200，返回原文件二进制，不使用统一 JSON 成功包装；Content-Type=application/octet-stream，Content-Disposition=attachment（UTF-8 安全原名），Content-Length 为字节数，Cache-Control=no-store、X-Content-Type-Options=nosniff。员工只能下载本人提单的公开附件；内部附件对员工、他人工单附件对员工、不存在附件统一 404 RESOURCE_NOT_FOUND。失效会话 401，存储文件缺失/异常 503 DEPENDENCY_UNAVAILABLE，错误仍用统一 JSON。
- 上传增加 TICKET_ATTACHMENT / TICKET_INTERNAL_ATTACHMENT 操作，details 只含 attachmentId、fileName、size；员工的内部上传操作在分页/计数前过滤。文件名仅用于安全处理后的下载/展示，物理文件用随机 UUID 保存。成功附件不自动清理；事务回滚清理本次文件，进程异常/提交结果未知的残留保留人工核对，不提供暂存或删除接口。
- 前端附件区独立分页、按权限选择公开/内部、文件大小校验及鉴权下载；上传保留 File/FormData、可见性、原版本和幂等键以重试，提交中或结果不确定时锁定选择，版本/状态冲突保留文件及错误并刷新详情，等待用户确认。上传与回复/流转互相禁用；关闭后隐藏上传入口，已有可见附件仍可下载。

拟修改契约：

- 配合 M03 拒绝状态，REJECTED 禁止回复、内部备注和上传，返回 409 INVALID_STATE，既有可见记录、附件列表和下载继续有效；不新增附件删除、预览或回复编辑接口。此状态联动尚未实现。
- 已确认不增加评论或附件删除接口，也不新增负责人修改原始 description 的接口；原始描述继续从详情只读获取，后续补充或纠错使用公开 replies，不扩展现有正文和操作权限。

功能链路：

- 沟通：打开可见工单 → 读取回复及操作记录 → 选择公开回复或有权限的内部备注 → 提交文字与版本/幂等键 → 校验身份及可写状态 → 保存并刷新；员工仅能操作本人单的公开内容。
- 补充原始问题：负责人查看原始描述 → 在公开回复说明补充或纠正信息 → 按回复权限、版本和幂等规则保存 → 原始描述与补充记录共同保留；不覆盖原文、不删除既有沟通。
- 附件：选择文件与允许的可见性 → 检查大小 → 携版本及独立幂等键上传 → 保存元数据和操作记录 → 按权限列出 → 鉴权下载；结果未知则重试同一上传，明确失败按 M04 规则处理。
- 只读：CLOSED 或拟新增 REJECTED → 隐藏写入口并由服务端拒绝新写入 → 仍按权限读取既有记录和下载附件，内部内容仍对员工隐藏。

### M05 列表与检索（前后端已实现）

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
- 前端 `/tickets?view=…` 对接列表、筛选、搜索和分页，`/tickets/{id}` 展示真实详情及解决摘要并接入 M03 操作区和 M04 回复/备注/附件/操作记录；提单成功可进入详情。
- 列表项和详情增加 `sla`，字段及计算规则见 M07；权限、分页和其他字段不变。

拟修改契约：

- 配合统一类型，拟用 businessTypeId 筛选并返回稳定类型标识及现名，保留已删除类型的历史查询能力，旧文本迁移与 M02 同步；现行 businessType 文本筛选尚未改造。
- 配合 M03，拟增加 status=REJECTED 筛选，详情返回 rejectionReason/rejectedAt；维持现有工单可见范围。拒绝不代表删除，历史视图仍可查；候选负载和交接排除已拒绝单。
- 拟新增 lifecycle=OPEN/ENDED/ALL，ASSIGNED 省略时默认 OPEN，其他视图省略时默认 ALL。OPEN 为 NEW/IN_PROGRESS/ON_HOLD/RESOLVED，ENDED 为 CLOSED/REJECTED；ALL 不附加结束范围筛选。仍可显式切换 ALL/ENDED 查询历史，不改变负责关系及可见权限。精确 status 与 lifecycle 取交集，不匹配时返回空结果；前端切换范围时同步展示对应可选状态，避免隐藏冲突筛选。
- 拟新增 createdFrom、createdTo（YYYY-MM-DD，均可单独省略），按产品既定 Asia/Shanghai 的创建日期过滤，起始日包含、结束日包含，数据库使用起始日零点至结束日次日零点的左闭右开时间段；非法日期或起日晚于止日返回 400 INVALID_QUERY。新增 priority=P1/P2/P3/P4，非法值同样返回 400。
- 拟新增 requesterId、assigneeId（UUID，精确筛选），仅 ENGINEER/ADMIN 可使用，员工提交人员筛选返回 403 FORBIDDEN，不以参数替代可见范围校验。可查已停用或变更角色成员关联的历史工单，不把查询目标等同于可分派目标；非法 UUID 返回 400 INVALID_QUERY，无匹配数据返回空列表。
- 为筛选提供拟新增 `GET /api/v1/tickets/filter-options`：全角色获取可选及可见历史关联的 businessTypes（id/name/active）；仅工程师、管理员额外返回可见工单关联的 requesters/assignees（id/name），员工不返回人员名单。历史类型和人员按稳定 ID 查询，不返回邮箱、技能或账号管理字段，不复用管理员专属 GET /users 放开员工/工程师成员管理。所有选项仅辅助页面，实际查询仍验证视图和权限。
- 上述筛选全部可组合，保持 page/pageSize/total 及 createdAt/id 倒序不变，不增加导出或客户端自定义 SQL 排序能力。创建时间、人员、优先级及待办范围尚未实现。

功能链路：

- 查询：登录 → 选择有权使用的视图 → 组合状态、类型及关键词 → 服务端先按可见范围过滤再分页 → 展示结果 → 打开详情读取当前版本；员工请求团队视图被拒绝。
- 历史类型与拒绝查询（尚未实现）：选择历史类型或 REJECTED → 按稳定类型关联、现名及状态筛选 → 展示可见历史工单 → 拒绝详情只读显示公开原因与时间。
- 扩展筛选（已确认，尚未实现）：读取有权取得的筛选选项 → 选择日期、优先级、状态、类型及允许的人员条件 → 服务端校验并与视图取交集 → 分页展示 → 清除筛选或打开详情；日期范围按完整自然日计算。
- 待办与历史（已确认，尚未实现）：进入“我的待办”默认 OPEN → 展示本人未结束负责单 → 切换 ALL 或 ENDED → 展示本人全部或已结束历史 → 按原权限查看记录，不把历史单移出数据库或清空负责人。

### M06 邮件通知（已实现，无独立前端接口）

邀请、重发邀请、重置申请继续调用 M01 既有接口写入任务；工单通知由既有 M03/M04 写接口在同一事务内入队，不新增通知查询、投递或重试接口，不改变工单请求/响应字段。后台在显式启用且配置就绪后投递和重试，接口成功不等于收件箱已收到。

- `assignments`、`transfers` 通知提单人及操作后的新负责人；公开 `replies`、`resolutions`、`close`、`reopen` 通知提单人及操作后的当前负责人。收件人去重、排除操作者，入队时仅保留 ACTIVE 且可见的成员；发送前再校验账号状态和工单可见性。负责人为空时只处理提单人候选。
- 当前新建、claim、suspensions、resume、priority-changes、内部备注、附件、SLA 超时不通知。已确认但尚未实现的例外：M02 创建时指定负责人按 assignments 通知，排除提单人后仅通知新负责人，自提自接不入队；无负责人创建仍不通知。业务校验失败、版本冲突、事务回滚不入队；成功幂等重放不重复入队。任务入队失败沿用统一 503，并回滚业务、审计及成功幂等记录，不返回虚假的成功结果。
- 中文纯文本工单邮件只含编号、动作、`/tickets/{id}` 登录鉴权详情链接；不含标题、描述、回复正文、原因、内部备注、附件或账号令牌。
- 复用 notification_task、领取锁、120 秒租约、4 次尝试及 60/300/900 秒退避；发送前无权访问的任务结束为 FAILED，最后一次崩溃后租约耗尽为 TIMEOUT。SMTP 接受后回写失败可能重复发送，不保证恰好一次。
- `OPENITSM_ACCOUNT_MAIL_ENABLED` 为既有邮件投递总开关；工单通知另需显式开启 `OPENITSM_TICKET_MAIL_ENABLED`（默认 false）。关闭时工单任务仍随业务入队，但不领取、不耗费尝试；启用前应核对积压任务及收件人。配置说明见 source/backend/README.md。

M14 的 GET /api/v1/settings 及邮件配置状态页面尚未实现，不作为配置入口。

拟修改契约：

- 配合 M02 初次指定负责人复用 assignments 入队，排除操作者，自提自接不产生任务；该入口联动尚未实现。
- M03 拒绝动作明确不发邮件，不向通知动作白名单加入 rejections；拒绝原因仅在系统内按权限展示。

功能链路：

- 通知：授权业务动作 → 同事务保存业务、审计及通知任务 → 返回受理成功 → 后台在配置允许时领取 → 复核收件人状态及工单权限 → 发送 → 回写结果；失败按既有间隔重试，入队失败回滚业务，投递失败不撤销业务。
- 无通知：操作者被排除后无收件人，或动作属于明确不通知范围（含拒绝） → 业务正常提交 → 不生成邮件任务。

### M07 简单处理统计（目标已确认，现行 SLA 待替换）

现行已实现契约如下，尚未按新目标替换。当前无独立接口，SLA 时限与超时标识随工单数据返回：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/tickets | 列表项含响应、解决时限的超时标识 | 同 M05 |
| GET | /api/v1/tickets/{id} | 详情含各时限的目标时间点与超时状态 | 同 M05 |

参数要点：

- 时限由优先级决定：P1 15 分钟 / 4 小时，P2 1 小时 / 8 小时，P3 4 小时 / 24 小时，P4 8 小时 / 72 小时。
- 列表项和详情统一增加 `sla` 对象：`responseDueAt`、`resolutionDueAt` 为本轮目标时间，`firstRespondedAt`、`resolvedAt` 为本轮实际完成时间（未完成为 null），`responseOverdue`、`resolutionOverdue` 为布尔值。时间为带 UTC 标识的 ISO 8601，页面按 Asia/Shanghai 显示。
- 首轮从创建起算，后续从重开时间 `cycleStartedAt` 起算，7×24 自然时间；挂起、分派、转派不暂停或重置。响应仅记录回复时角色为 ENGINEER/ADMIN 的首条非空公开文字回复，包括回复本人的单；内部备注、附件、受理和解决摘要不算首次响应。
- 超时比较使用实际完成时间，尚未完成则用本次查询时间；严格超过目标才为 true，等于目标为 false。响应与解决独立判断，解决/关闭不虚构首次响应。解决后若仍无首次响应，该响应指标继续以查询时间判断。
- 重开清空 `firstRespondedAt`、`resolvedAt`，更新本轮起点；保留已有公开解决摘要。调整优先级按本轮起点及新优先级重算，已完成指标也按新目标重新判断。
- 写入实际时间与既有回复/流转、审计、版本及幂等记录同事务提交，重放不覆盖时间。GET 只计算返回值，不写数据库、不增加版本或延长会话；沿用 M05 的权限和错误。
- 用户确认仅对新工单保证完整指标。V7 升级前的旧工单返回 `sla: null`（历史数据不足），不补造历史时间、不删除数据；旧工单重开后开始完整记录。
- 前端列表显示响应/解决超时、达标或计时中；详情显示目标、实际时间及状态，可手动刷新。状态以最近一次加载为准，不使用浏览器时钟自行判定。

拟修改契约（已确认，尚未实现）：

- M07 只定义统计口径，由 M13 既有规划 GET /api/v1/dashboard/metrics 统一承载，不新增重复统计接口；移除目标中的固定时限、超时和达标率，不增加评分接口。
- 解决耗时样本为当前 RESOLVED/CLOSED 且 resolvedAt 非空并不早于 createdAt 的工单，逐单秒数为 resolvedAt-createdAt；总秒数除以有效样本数得到平均值。拒绝单、重开未再次解决单及历史时间缺失单不混入；无样本返回 null 并返回样本数 0，不用 updatedAt、closedAt 或 rejectedAt 冒充解决时间。
- 状态及业务类型计数覆盖查询范围内的全部单；人员未结束负载为当前负责的 NEW/IN_PROGRESS/ON_HOLD/RESOLVED，未分派单独计数，拒绝及关闭不计负载。默认全部，日期筛选统一按 createdAt 创建日期，口径与 M13 相同。
- 后续实施需同步 M05 返回和前端展示，停止提供目标时间/超时标记；resolvedAt 作为真实解决时间保留，拒绝另存时间。旧 sla 字段的响应兼容、旧前端切换和存储字段处理在实施前明确迁移，本轮不直接删列或改旧迁移，现行接口仍按上文运行。

功能链路（尚未实现）：

- 统计：管理员打开看板 → Java 校验查询条件 → 对同一工单范围聚合数量、分布和人员负载 → 过滤有效解决样本 → 计算平均耗时及样本数 → 通过 M13 一次返回并展示。
- 时间缺失：历史单无真实解决时间 → 仍参与状态/类型计数 → 排除耗时样本 → 返回有效样本数；无有效样本显示暂无数据，不伪造零耗时。
- 重开或拒绝：重开后未再次解决 → 暂不计解决样本 → 再次解决后用最新 resolvedAt 减最初 createdAt；拒绝只计对应状态数量，不计解决耗时。

### M08 文本知识库（纯文本前后端已实现，附件未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/knowledge-articles | 创建知识草稿 | 工程师 |
| PATCH | /api/v1/knowledge-articles/{id} | 编辑草稿 | 工程师（草稿作者） |
| POST | /api/v1/knowledge-articles/{id}/publish | 发布知识 | 管理员 |
| POST | /api/v1/knowledge-articles/{id}/unpublish | 下架知识 | 管理员 |
| GET | /api/v1/knowledge-articles | 按标题、正文、分类检索已发布知识 | 全角色 |
| GET | /api/v1/knowledge-articles/{id} | 查看知识详情 | 已发布对全角色；草稿限作者、管理员 |

参数要点：

- 创建与编辑均填写 `title`、`content`、`category`，三项必填字符串，去除首尾空白后保存，按纯文本展示，分类自由填写。沿用全局 JSON 大小限制。
- 状态只有 `DRAFT`、`PUBLISHED`。工程师仅可创建及编辑本人草稿；已发布内容不能直接修改。管理员负责发布、下架，下架回草稿并清空 `publishedAt`，原作者工程师可修改后再次发布。
- 列表参数 `view=PUBLISHED`（默认，全角色，仅已发布）、`MINE`（仅工程师，本人全部状态）、`ALL`（仅管理员，全部知识）；`q` 去除首尾空白后为 1～100 个 Unicode 字符，标题或正文不区分大小写的字面子串匹配；`category` 为非空分类精确匹配；`page` 为正整数，默认 1，固定每页 20 条，按 `updatedAt`、`id` 倒序。
- 列表 `data` 为 `{items,page,pageSize,total}`；列表项和详情均含 `id,title,content,category,status,authorId,authorName,version,createdAt,updatedAt,publishedAt`。草稿的 `publishedAt` 为 null，发布时记录本次发布时间。
- 草稿详情仅原作者工程师及管理员可读；员工（包括已降级的原作者）或其他工程师访问草稿返回 404。下架内容从默认检索中消失，仍可在获授权的 MINE/ALL 中查看。
- 写入沿用全局幂等、版本及 Cookie/CSRF 规则：创建带 `Idempotency-Key`，编辑/发布/下架另带 `If-Match`；发布/下架无正文。创建 201，其余 200，`data` 为 `{resourceId,acceptedVersion,replayed}`，附 ETag；重放返回原版本，不重复执行。版本冲突 409 `VERSION_CONFLICT`，状态不允许 409 `INVALID_STATE`，无角色权限 403，非法查询 400 `INVALID_QUERY`。读取无写入或会话续期副作用。

拟修改契约：

- 已确认保留现有作者工程师草稿、管理员发布/下架及已发布只读规则；正文仍为纯文本。知识附件随文章授权，不复用工单 ID 或工单可见性直接授权文件。
- 分类继续自由文本，不因 M02 业务类型改统一下拉而自动改造知识分类；知识附件不进入 AI 输入，遵守项目附件隔离边界。

知识附件拟新增接口（均未实现）：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/knowledge-articles/{id}/attachments | 上传草稿附件 | 原作者工程师，仅本人 DRAFT |
| GET | /api/v1/knowledge-articles/{id}/attachments | 查询未移除附件 | 随文章读取权限 |
| GET | /api/v1/knowledge-attachments/{attachmentId} | 鉴权下载未移除附件 | 随文章读取权限 |
| DELETE | /api/v1/knowledge-articles/{id}/attachments/{attachmentId} | 草稿中逻辑移除附件 | 原作者工程师，仅本人 DRAFT |

- 上传仅接受 multipart 的单个 file 字段，不接受 visibility；大小 1～104857600 字节（100 MiB，含上限），空文件或格式非法 400 VALIDATION_FAILED、超限 413 REQUEST_TOO_LARGE。不限制扩展名，不提供预览或执行；工单附件仍限制 10 MiB，不能因放宽知识上传而一并放开。
- 上传与移除均需文章 If-Match、Idempotency-Key；成功拟返回既有 resourceId（文章 ID）、acceptedVersion、replayed 和 ETag，递增文章 version、updatedAt，不自动发布。上传摘要包括原文件名/内容 SHA-256/文章原版本，移除摘要包括附件 ID/原版本。正文、附件变更和发布均锁同一文章，防止并发发布遗漏或夹带未经确认的附件。
- 已发布文章禁止附件变更，409 INVALID_STATE；管理员可下架并读取草稿附件，但无作者工程师资格时不允许上传或移除。不可见文章、附件不存在、归属不匹配或已移除附件统一 404；对可见草稿无编辑权的写入为 403。列表仅返回未移除项，按 createdAt/id 升序、固定每页 20，包含 id/fileName/size/uploaderId/uploaderName/createdAt，不返回存储路径或哈希。
- 发布后任何登录角色可按文章权限列出和下载；下架后立即回到作者工程师/管理员可读范围。下载沿用 M04 的 application/octet-stream、安全 Content-Disposition、no-store/nosniff 和二进制响应，不沿用工单的权限条件。草稿移除仅记录 removedAt/removedBy，保留文件和历史元数据，后续普通列表/下载不返回已移除项；不新增恢复、物理删除或历史文件下载入口。
- 写入幂等重放先返回首次成功结果，不重复存储或移除；未知结果保留文件、原版本与键重试。后续文章已发布或附件已移除时，原成功请求仍可按授权重放，不能借重放新增写入。新请求必须重新校验状态；版本冲突由用户刷新确认。新文件失败补偿沿用既有回滚清理，不清理已成功或逻辑移除文件。

功能链路：

- 创作：工程师创建本人草稿 → 填写纯文本标题、正文、分类 → 保存并取得版本 → 管理员检查后发布 → 全角色按关键词和分类检索并阅读。
- 修订：管理员下架 → 文章回到 DRAFT，员工不再可读 → 原作者工程师修改并保存 → 管理员重新发布 → 展示最新已发布内容；版本冲突先刷新并由用户确认。
- 附件上传（已确认，尚未实现）：作者工程师打开本人草稿 → 选择不超过 100 MiB 的文件 → 校验并携文章版本/独立幂等键上传 → 保存附件并更新文章版本 → 管理员检查文章及附件后发布 → 全角色按文章权限下载。
- 附件修订（已确认，尚未实现）：已发布文章 → 管理员下架 → 仅作者工程师/管理员可读 → 作者工程师上传或逻辑移除 → 保存更新版本 → 管理员重新发布；被移除文件保留存储但不再可下载。
- 附件失败重试（尚未实现）：上传失败或未知 → 保留 File、文章原版本和键 → 按原请求确认结果 → 明确冲突时刷新并由用户确认 → 成功后才进入发布流程，不把本地待上传文件列为已发布附件。

### M09 AI 业务类型建议（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/classification-suggestions | 生成已有业务类型及优先级建议 | 同下述类型确认角色/状态范围 |
| POST | /api/v1/tickets/{id}/classifications | 人工确认业务类型，不写优先级 | NEW 提单人/负责人/管理员；处理中或挂起的负责人/管理员 |

参数要点：

- 建议接口返回的均为建议值，不直接生效；分类即 businessTypeId，不再增加平行分类或标签。
- classifications 拟仅含确认后的 businessTypeId，不能写入 priority；优先级建议由管理员通过 M03 priority-changes 确认并填写原因，其他角色不得绕过。类型只能选已有可用项，AI 不自动创建类型。
- AI 不可用或已关闭（M14）时不影响人工提单与处理。

拟修改契约：

- 已确认取消标签及独立分类字段；M09 复用 M02 业务类型选项，管理权限不因 AI 请求扩大。NEW 限提单人、当前负责人或管理员，IN_PROGRESS/ON_HOLD 限当前负责人或管理员；RESOLVED/CLOSED/REJECTED 禁止类型写入及本入口建议请求。操作角色和目标状态在服务端检查，不仅隐藏按钮。
- classifications 正文仅 businessTypeId（必填 UUID），需 If-Match/Idempotency-Key；同事务更新类型、version、TICKET_CLASSIFICATIONS 审计及成功幂等记录，状态、负责人、优先级不变且不触发邮件。成功 200，返回现有 resourceId/acceptedVersion/replayed 与 ETag；不可见 404、无权限 403、状态不允许 409、版本/幂等冲突沿用通用规则，类型不存在/不可用或字段非法 400。类型提交前失效则整次失败，不静默改为其他类型；原成功请求重放不重复写入。
- classification-suggestions 不接受客户端工单正文或自定义类型集合，由 Java 按路径工单取公开问题字段与可用类型；本接口不写业务字段，不要求 If-Match。建议响应拟含 businessTypeId、reason、suggestedPriority，前两者可为空表示无可用建议，优先级建议仅 P1～P4 或 null；前端明确“建议尚未生效”。AI 重试与错误遵循 M14 共用约定，本版无每日次数额度，不用 suggestions 接口代替类型管理。
- AI 输入仅使用经授权的工单公开问题信息及可用类型选项，不读内部备注或任何附件。输出候选类型与简短依据、可选优先级建议；Java 过滤非法/失效类型、拒绝非预期字段，建议不更新工单或版本。

功能链路（尚未实现）：

- 建单后打开有权操作的工单 → 手动生成类型/优先级建议 → Java 校验可见性并提供可用类型 → Python 计算 → Java 校验输出 → 人工确认已有类型 → 版本/权限/目标有效性校验后保存；优先级单独由管理员填写原因经 M03 调整。
- AI 不可用 → 显示说明并保留当前工单 → 有权限者仍可人工选择业务类型 → 同样通过分类确认校验，不自动创建标签或类型。
- 状态或角色变化：页面生成建议后工单已被受理、解决或负责人变化 → 提交时重新检查权限、状态和版本 → 不满足则拒绝并刷新，建议不会自动覆盖新数据。

### M10 处理人推荐（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/assignee-recommendations | 返回最多 3 名候选处理人及推荐依据 | 工程师、管理员 |

参数要点：

- 候选为有效处理人（ENGINEER/ADMIN，与 M03 调整同步），推荐结合已解决单经验与当前负载，并参考本人技能描述。
- 推荐不自动分派；创建前通过 M02 assigneeId 人工确认，建单后管理员分派用 M03 assignments，负责人按既有权限转派用 transfers。
- 负载数据同 GET /api/v1/users/assignees。

拟修改契约（已确认产品范围，尚未实现）：

- 保留建单后推荐，同时拟新增 `POST /api/v1/ai/assignee-recommendations`，全部登录角色可用，需现有会话与 CSRF。正文为 title、description、businessTypeId、impactScope，均来自当前表单；不接受附件、内部备注或客户端自定候选名单，不依赖工单 ID。
- Java 校验输入、类型有效性并取得有效 ENGINEER/ADMIN 候选，再调用 Python 计算。拟返回 data.items，最多 3 项，每项 assigneeId、name、reason；只允许候选集合内的有效处理人。历史经验仅为候选人按业务类型汇总的当前 RESOLVED/CLOSED 工单数量，加当前未结束负载及技能描述；不提供历史工单正文、账号邮箱或无权读取的记录。原负责人变动后的历史归属使用当前保留的 assigneeId，不声称还原全部实际参与者；业务类型按稳定 ID 聚合。本版无每日次数额度，超时及调用错误见 M14。
- 已确认将候选人自行维护的 skillsDescription 作为历史经验和负载之外的补充依据，允许为空；由 Java 从有效候选中读取并作为不可信文本提供，不接受浏览器指定他人的技能，不从技能文本执行指令。前后推荐共用此依据，响应仍只返回候选与简短理由，不增加全角色读取成员技能详情的能力。
- 推荐请求只产生建议，不创建工单、不指定负责人、不触发分派通知。用户手动确认后使用 M02 assigneeId；表单改变后原建议应提示已过期，创建仍以当前表单和服务端最终校验为准。无候选、AI 关闭或失败不阻止人工提单。
- 建单后推荐沿用既有设计的工单路径与工程师/管理员调用范围，但“取得推荐”不授予分派权限：管理员可按 M03 分派，负责人只能执行已授权的转派动作，其他工程师不能据此改派。

功能链路（均尚未实现）：

- 创建前：全角色填写表单 → 手动请求推荐 → Java 校验并提供授权候选 → Python 返回建议 → Java 过滤候选与可展示理由 → 提单人确认 → M02 创建时校验并指定。
- 创建后：工程师或管理员打开可见工单 → 请求推荐 → 查看候选与理由 → 有权操作者按 M03 分派或转派 → 校验版本及目标 → 生效；无操作权限仅能查看建议。
- 降级：无候选、AI 关闭或失败 → 显示说明 → 保留当前表单或工单 → 人工选择或按允许的流程不指定提交，不自动选择替代人。

### M11 相似问题检索（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/similar-issues | 返回最多 5 条相似来源 | 工程师、管理员 |

参数要点：

- 结果来自已解决工单与已发布知识，仅含调用角色可见的内容。
- 每条结果标注真实来源（工单编号或知识 ID）。

拟修改契约：

- 请求使用路径中的当前工单，由 Java 验证工程师/管理员及工单可见性，读取标题、原始描述、业务类型等公开问题字段；不接受浏览器自定全库语料或他人工单正文。接口只检索，不改变状态、负责人、版本，不要求 If-Match。
- 候选来源仅当前 RESOLVED/CLOSED 工单的可见标题、原始描述和公开解决摘要，以及当前 PUBLISHED 知识的标题和正文；排除当前工单、REJECTED、未解决单、知识草稿、内部备注及所有附件。Java 从数据库查询授权候选，再由 Python 计算相关性，不把全库交给模型自行查。
- 拟返回 data.items，0～5 项，含 sourceType（TICKET/KNOWLEDGE）、sourceId、number（仅工单）、title、excerpt、reason；标识必须来自真实候选，链接由前端按来源类型和 ID 构造，不采纳模型伪造的链接或来源。结果去重，返回前再次复核来源状态和可见性，已下架或失去资格的来源剔除，不为凑足 5 条补造结果。
- 无匹配返回 200 和空 items；AI 关闭或计算失败显示不可用并允许使用 M05/M08 的人工关键词检索，不把依赖失败伪装为空结果或成功检索。具体 AI 错误和主动重试遵循 M14 共用约定，本版无每日次数额度；依赖故障不修改当前工单。

功能链路（尚未实现）：

- 工程师或管理员打开可见工单 → 主动检索 → Java 查询已授权的解决单/已发布知识候选 → Python 判断相关性 → Java 校验真实来源、状态和权限 → 返回最多 5 项 → 用户打开真实详情核对。
- 无命中 → 展示暂无相似来源 → 继续人工检索或直接处理；服务失败 → 显示错误及重试/人工入口，两种结果明确区分。
- 来源变化：计算期间知识被下架或源工单重开 → 返回前重新检查 → 移除无效来源；用户后续点击仍通过真实详情接口校验，不靠缓存摘要绕过权限。

### M12 AI 回复草稿（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/reply-drafts | 基于当前问题与相似检索材料生成回复草稿 | 工程师、管理员 |

参数要点：

- 草稿不自动发送；人工核对修改后通过 M04 的 replies 发表，仅在用户当前页面保留。
- AI 不可用时不影响人工直接回复。

拟修改契约（已确认方向，尚未实现）：

- 工程师/管理员可对可见 NEW/IN_PROGRESS/ON_HOLD/RESOLVED 生成草稿，不要求本人负责；CLOSED/REJECTED 返回 409 INVALID_STATE，非处理角色 403，工单不存在/不可见 404。请求由 Java 读取路径工单和 M11 授权候选，不接受客户端注入他人工单正文、系统提示或候选来源。
- 生成不要求 If-Match，不写工单、回复、审计或版本，不触发通知；拟返回 data 的 content、sources（sourceType/sourceId/number/title）及 basedOnVersion。sources 只允许真实授权来源，最多沿用 M11 的 5 项，不包含内部备注或任何附件；Java 返回前再次验证工单状态和来源可见性，必要时拒绝过时生成结果。
- 草稿仅当前页面，服务端不保存业务草稿箱或增加草稿查询/恢复接口。用户编辑后经 M04 replies 发送，使用发送时最新版本和独立 Idempotency-Key；不得把“生成草稿”视为已经回复或首次人工响应。生成期间工单变化则提示重新核对，不能自动拿新版本覆盖提交。
- AI 关闭/超时/失败明确提示并保留人工回复入口，不发空草稿冒充成功。已确认无相似来源时可基于当前问题生成补充信息询问或一般建议，sources=[] 并明确标注“无参考来源”，不编造引用、历史案例或已验证结论；拟返回 hasReferenceSources=false 供页面明确展示，有来源时为 true。重新点击生成属于新的 AI 请求，不保证原结果重放，页面需避免自动重试导致重复调用。

功能链路（尚未实现）：

- 工程师或管理员打开可回复的可见工单 → 主动生成 → Java 校验角色/状态并取得公开问题与授权来源 → Python 生成 → Java 核验来源及当前状态 → 页面显示可编辑草稿和真实来源 → 用户核对、修改 → 通过 M04 公开回复发送 → 成功后才生成正式回复及相应通知。
- 已解决待确认：RESOLVED → 可生成并人工追加回复 → 状态仍 RESOLVED；已关闭/拒绝则禁止生成和发送。
- 丢失或失效：刷新/离开页面后草稿不保证保留；生成后工单关闭/拒绝或版本变化 → 发送时再次校验 → 提示冲突/只读，不自动发送；用户仍可手工复制未提交文字供核对。
- 无相似来源：M11 确认无匹配 → 使用当前问题生成一般建议或补充询问 → 展示无参考来源 → 人工核对修改后按 M04 发送；M11 服务失败则走失败提示，不冒充正常无命中。

### M13 基础看板（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/dashboard/metrics | 看板统计 | 管理员 |

参数要点：

- 目标统计内容：工单总数、状态/业务类型分布、未分派量、人员未结束负载及平均解决耗时，口径统一由 M07 定义；不提供固定 SLA 目标、超时、达标率或评分。

拟修改契约（已确认方向，尚未实现）：

- 继续使用 GET /api/v1/dashboard/metrics，仅管理员可调用；无业务写入、审计新增或会话续期副作用，员工及工程师访问返回 403。拟支持 createdFrom/createdTo（YYYY-MM-DD，均可省略，默认全部），与 M05 同样按 Asia/Shanghai 自然日、起日含/止日次日零点前计算；非法日期或起日晚于止日返回 400 INVALID_QUERY。数量、分布、负载和解决耗时基于同一创建日期范围，不增加解决日期筛选。
- 拟返回 data 的 asOf、totalCount、countsByStatus、countsByBusinessType、unassignedCount、assigneeLoads、averageResolutionSeconds、resolutionSampleCount。状态覆盖 REJECTED；类型按稳定 ID 关联现名，保留已删除类型。人员项仅 id/name/openCount，不返回邮箱或技能；历史负责人按稳定 ID 归类，不按姓名合并。
- 平均耗时及样本排除规则见 M07；无工单时总数/计数为 0，平均耗时为 null。未分派量只计未结束且 assigneeId 为空的工单；asOf 明确本次统计时点，相关聚合使用一致的读取视图，避免同次响应的总数和分布互相矛盾。

功能链路（尚未实现）：

- 管理员进入看板 → 选择已确认的统计范围 → 调用 metrics → 展示总量、状态/类型分布、人员负载和平均解决耗时及样本数 → 手动刷新；无样本显示暂无数据，失败显示错误及重试，不使用占位数字冒充真实统计。

### M14 最小配置（未实现）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/settings | 读取团队配置与 AI 调用状态 | 管理员 |
| PATCH | /api/v1/settings | 更新团队配置 | 管理员 |

参数要点：

- 更新正文含团队名称、邮件连接、模型连接、AI 开关。
- GET 返回上述配置及只读的 AI 调用状态。

拟修改契约（已确认方向，尚未实现）：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/settings/model-connection-tests | 使用已保存连接发送固定测试文本 | 管理员，AI 关闭时也允许 |

- GET settings 拟返回 id/version、teamName、smtp（host/port/username/from/security/passwordConfigured）、model（baseUrl/model/apiKeyConfigured）、aiEnabled 及只读调用/测试状态。SMTP 密码和模型密钥不回显，不把掩码字符串当作可回传凭据；后台状态不得包含原始密钥、工单正文、完整供应商请求或错误堆栈。
- PATCH 拟接受 teamName、smtp、model、aiEnabled 的局部更新，至少一项，需 If-Match/Idempotency-Key；管理员权限与版本重验后事务内保存，200 返回既有 resourceId/acceptedVersion/replayed 与 ETag。teamName 提供时须为非空字符串；端口为 1～65535，连接地址/安全模式与服务器连接要求校验，非法字段为 400。清除凭据后不具备模型连接条件时不得保持 AI 启用；未配置完整连接时开启返回 409 INVALID_STATE，不静默发送外部请求。
- 凭据字段省略表示保留，非空值表示替换；smtp.clearPassword/model.clearApiKey=true 才表示显式清除。清除与同时提供新值互斥，空串或 null 不作为清除命令，避免掩码或空表单误覆盖。凭据加密后保存数据库，运行按配置版本使用；页面不改 .env，数据库未保存前可使用部署引导配置。首次局部保存以当前有效配置为基线保留省略项，已有数据库配置优先，显式清除后不回退旧环境密钥。
- 保存设置只保存和校验，不自动测试、发送邮件或开启 AI；AI 开关必须由管理员显式选择。SMTP 参数接入现有投递器，已有投递开关和收件权限保护继续适用，不因界面保存连接而自动启动积压投递。模型只配置一个连接，不支持页面填写脚本或浏览器直接调用 Python。
- model-connection-tests 不接受业务正文或任意提示，只测试当前已保存的连接，返回测试状态及 testedVersion；配置缺失返回明确错误，失败不改变连接或启用状态。对同一管理员拟限制每 60 秒一次，超限 429 RATE_LIMITED/Retry-After；只记录脱敏结果，配置已变化时旧测试结果不作为新配置可用的证据。
- 本版不设每日次数、个人或团队额度，不增加额度扣减/预占表或“额度耗尽”错误。读取只显示启用、已配置及最近调用/测试状态；连接未测试或无调用时明确“未测试/暂无调用”，不能显示虚假的成功。

M09～M12 共用的拟定调用约定：

- 纯计算建议/检索/草稿请求使用既有会话、Origin 和 CSRF，不要求 If-Match 或业务 Idempotency-Key；不写工单版本。人工应用业务类型、分派、回复仍各自带版本与幂等键。页面提交中禁止重复点击，失败由用户主动重试，不自动重试外部生成；重新生成是新计算，不承诺恢复原草稿或原建议。
- 统一包裹响应及 requestId；AI 关闭拟返回 503 AI_DISABLED，缺配置 503 AI_NOT_CONFIGURED，输出结构/候选来源非法 503 AI_INVALID_RESPONSE，Python/模型依赖故障 503 DEPENDENCY_UNAVAILABLE，有限等待超时 504 AI_TIMEOUT。上述为拟新增模块契约，现有未实现接口不会返回成功或伪造数据；不将错误伪装成空结果。
- Java 和 Python 设置有限连接/读写/总等待并及时取消，具体传输值在部署接入时校验；只回传可公开的错误说明，不回显密钥或原始供应商异常。AI 关闭阻止后续业务调用，已在途结果返回前重验开关/权限，关闭后不自动应用结果；管理员连接测试是独立例外。

功能链路（均尚未实现）：

- 配置：管理员读取设置和版本 → 修改团队/SMTP/模型参数，按需替换或显式清除凭据 → 校验权限、版本及配置完整性 → 加密保存 → 返回受理版本 → 后续服务按保存配置运行，页面只显示凭据已配置状态。
- 测试：管理员保存模型连接 → 主动测试（AI 可关闭） → 发送固定测试文本 → 脱敏展示对应配置版本的结果 → 管理员决定是否开启，不自动切换开关或保存别的表单修改。
- 开关与人工降级：管理员关闭 AI → 新业务 AI 请求明确不可用 → M02/M03/M04/M05/M08 人工流程继续；管理员开启前须配置完整连接，无调用记录时显示暂无调用。

## 内部接口（Java → Python，M09～M12 及 M14 模型测试）

以下接口由 Java 后端调用 Python AI 服务，浏览器不可直接访问；不适用本文档的浏览器会话和统一响应包装。均为未实现的设计契约，材料范围和输出限制遵循对应模块；实施时须补齐并验证具体传输结构，不能绕过 Java 权限和输出过滤。

| 方法 | 路径 | 用途 | 对应前端接口 |
| --- | --- | --- | --- |
| POST | /internal/v1/classification-suggestions | 已有业务类型与优先级建议，不含标签 | M09 classification-suggestions |
| POST | /internal/v1/assignee-recommendations | 处理人候选推荐 | M10 assignee-recommendations |
| POST | /internal/v1/similar-issues | 相似问题检索 | M11 similar-issues |
| POST | /internal/v1/reply-drafts | 回复草稿生成 | M12 reply-drafts |
| POST | /internal/v1/model-connection-tests | 固定文本连接测试，不使用工单材料 | M14 model-connection-tests |

Python 侧只做 AI 计算，不访问数据库；输入材料（授权公开问题、合法处理人及汇总经验/负载/技能、授权检索语料）由 Java 组装后传入。模型连接凭据只供服务端传输客户端认证，不作为提示词材料，不提供 SMTP 或账号凭据，不写日志或响应；测试只走固定文本的独立内部入口，不借业务提示注入。内部调用需限制为服务端访问并校验调用方，最终输出由 Java 按角色、状态和候选集再次过滤。
