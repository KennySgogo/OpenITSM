# OpenITSM API

面向前端的接口文档。M01～M12 下列契约已接入源码，实际验收边界见 STATUS；M13 统计接口随 M07 接入，M14 设置已接入。

- 后端基础地址（本地）：`http://127.0.0.1:8080`
- 接口前缀：`/api/v1`
- 当前已实现：认证、成员管理、内部提单（M02 前后端）、工单流转（M03 前后端）、回复/内部备注/附件/操作记录（M04 前后端）、列表详情与检索（M05 前后端）、简单统计（M07/M13）
- M06 已接入账号邮件与工单通知的后台投递；M08～M12、M14 已接入，M13 统计已接入。

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
| X-Request-ID | 可选 | UUID；非法值被忽略 |

### 请求正文

| 项 | 约定 |
| --- | --- |
| 编码 | UTF-8 JSON |
| 未知字段 | 一律拒绝（400 VALIDATION_FAILED），字段名必须与本文档一致 |
| 大小上限 | 256 KiB，超出返回 413 REQUEST_TOO_LARGE |
| 可选字段 | 默认省略，只有模块明确允许时可传 null |

### 成功响应

统一结构（附件二进制下载与 204 除外）：

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
| 413 | REQUEST_TOO_LARGE | 请求正文超过 256 KiB | 前端代码缺陷 |
| 415 | MEDIA_TYPE_UNSUPPORTED | Content-Type 不是 JSON | 设置 application/json |
| 429 | RATE_LIMITED | 触发限流 | 按 Retry-After 等待后重试 |
| 500 | INTERNAL_ERROR | 服务内部错误 | 提示稍后重试，可附 requestId |
| 503 | DEPENDENCY_UNAVAILABLE | 数据库、邮件或 AI 服务不可用 | 提示稍后重试；retryable=true |

### 写入与并发

写请求使用现有会话、Origin 和 CSRF。服务端在事务内锁定相关业务行，校验当前角色、负责人及状态，再更新业务数据和操作记录。重复认领、关闭或发布由当前状态检查处理；相同负责人或业务类型的重复确认直接返回成功。

只有知识正文编辑需要在 JSON 正文提供 version，与读取的文章版本一致；缺失或过期返回 409 VERSION_CONFLICT，保留草稿并刷新后人工确认。其他写操作不要求客户端版本。读取响应中的工单/文章 version 用于页面刷新，设置 version 用于标记连接诊断结果对应的配置。

提交期间禁用重复点击。建单结果不确定时先到“我的工单”核对；其他追加操作先刷新记录，确认结果后再决定是否提交，不自动重放业务写入。

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

所有模块逐步统一为“接口表 + 参数要点 + 拟修改契约 + 功能链路”，后两项放在模块末尾，明确区分已实现与未实现。所有接口沿用通用约定（认证、CSRF、统一响应结构、全局错误码）；写入和并发规则见通用约定。

### M01 认证（本人维护扩展已接入源码，待运行验收）

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
- defaultPath 按角色：ADMIN→/dashboard、ENGINEER→/tickets?view=ASSIGNED、EMPLOYEE→/tickets?view=MINE；工单列表已实现，看板接入 M07/M13 统计。
- returnTo 校验（不通过则回退 defaultPath 并提示）：/knowledge、/tickets、/tickets/new、/tickets?view=MINE 全角色；/tickets?view=ASSIGNED、UNASSIGNED、ALL 非员工；/tickets/{id} 员工限本人工单，其他角色须工单存在；/dashboard、/admin/users、/settings 管理员。
- me：Body 与 login 成功响应相同（redirectPath 恒等于 defaultPath）；401 时跳登录页并携带 returnTo。
- activity：无正文，204；每 55～60 秒上报一次；同会话 60 秒内重复返回 429，忽略即可；只刷新无操作计时，不延长 absoluteExpiresAt；收到 401 停止上报并跳登录页。
- logout：无正文，204；成功后清空本地状态并跳登录页；同一账号其他设备的会话不受影响。
- password-reset-requests：正文 email；无论邮箱是否存在均返回 202 与统一文案，不得据此推断账号存在；邮件服务未配置返回通用 503。
- password-resets：正文 token（43 字符，申请后 30 分钟有效，一次性）、password（12～64 字符，至少一个字母和一个数字）、confirmPassword（须与 password 一致）；成功后该账号全部会话失效，必须重新登录；token 不存在、已使用或已过期返回 409 TOKEN_INVALID。
- activations：参数与密码规则同重置密码；邀请链接发出后 48 小时有效，重发邀请后旧链接失效；账号不处于待激活状态返回 409 TOKEN_INVALID。

补齐契约：

- 本人改密及资料维护接口已接入；登录、邀请激活及邮件找回原契约继续适用。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/password-changes | 校验旧密码并修改本人密码 | 全部登录角色 |
| GET | /api/v1/profile | 读取本人姓名与技能描述 | 全部登录角色 |
| PATCH | /api/v1/profile | 修改本人姓名与技能描述 | 全部登录角色 |

- password-changes 正文为 oldPassword、password、confirmPassword；只允许当前会话对应的本人，不接收 userId。新密码沿用 12～64 字符、至少字母和数字、二次输入一致的规则；旧密码错误或字段非法返回 400 VALIDATION_FAILED 并定位字段，会话失效仍为 401 SESSION_EXPIRED。使用现有 PasswordEncoder 校验/编码，校验失败不变更密码或会话；成功返回 204、清除当前 Cookie，并使本人全部会话失效。沿用改密对旧重置链接的失效处理；不在日志、审计中保存密码正文。
- 改密使用会话与 CSRF，凭据校验与更新在成员锁内完成，避免与重置密码并发覆盖。成功后旧会话不可重放；响应丢失时先用新密码登录确认，不盲目自动重试原改密。复用现有限流机制，独立按成员 5 次/15 分钟及来源 60 次/分钟，超限沿用 429/Retry-After，不把旧密码输错累计为登录失败。
- GET profile 返回本人 id、name、skillsDescription、version，不返回他人资料。PATCH 至少提供 name 或 skillsDescription，均为字符串：姓名去首尾空白后 1～80 字符，技能按纯文本、空串清空，整体沿用 256 KiB 限制；未知字段及 email/role/status/userId 均拒绝。事务内锁定本人记录后更新。
- 资料更新返回 resourceId，前端重读本人资料和会话。姓名或技能改变不撤销会话。技能仅供 M10 对合法候选的推荐参考，不开放员工读取其他成员完整资料的接口。

功能链路：

- 登录：进入登录页 → 获取 CSRF → 输入邮箱密码 → 校验凭据和账号状态 → 换发会话 → 返回有权限的原页面或角色默认页；失败显示统一错误，保留重试入口。
- 邀请激活：打开有效邀请链接 → 设置并确认密码 → 校验链接和密码 → 激活账号 → 返回登录页 → 用户自行登录；无效链接提示重新联系管理员。
- 找回密码：填写邮箱 → 统一返回受理提示 → 有效账号按邮件流程获取链接 → 设置新密码 → 校验一次性链接 → 更新密码并使旧会话失效 → 重新登录。
- 会话与退出：应用加载查询 me → 有效则恢复登录态并按真实操作上报 activity → 失效则返回登录页；主动退出仅结束当前会话。
- 本人改密：登录 → 打开修改密码入口 → 输入旧密码、新密码和确认密码 → 校验当前会话、CSRF、旧密码及新密码规则 → 同事务更新密码并撤销全部会话 → 返回登录页 → 用新密码登录；校验失败不改密码，响应未知时先登录确认。
- 本人资料：登录 → 读取本人资料及版本 → 修改姓名或技能描述 → 校验字段、本人身份、当前成员状态 → 保存 → 刷新资料与姓名展示，继续当前会话；冲突后刷新并由用户确认，仅更新本次提交的资料字段。

### M01 成员管理（已实现）

除 assignees 外均要求管理员；assignees 向全部登录角色开放。角色不足返回通用 403 FORBIDDEN。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/users | 分页查询成员 | 管理员 |
| GET | /api/v1/users/assignees | 查询有效工程师/管理员及当前负载 | 全角色 |
| POST | /api/v1/users | 邀请成员 | 管理员 |
| PATCH | /api/v1/users/{id} | 修改成员姓名或角色 | 管理员 |
| POST | /api/v1/users/{id}/disable | 停用成员 | 管理员 |
| POST | /api/v1/users/{id}/unlock | 解除登录锁定 | 管理员 |
| POST | /api/v1/users/{id}/invitation-resend | 重发邀请 | 管理员 |

参数要点：

- 查询：Query page（默认 1，须 ≥1）、q（去空格后 1～100 字符，同时模糊匹配姓名与邮箱，不区分大小写）、role、status；pageSize 固定 20，按更新时间倒序；page 或 status 非法返回 400 INVALID_QUERY。列表项含 version（供页面刷新）与 lockedUntil（仅晚于当前时间时展示解锁入口；2026-10-15 新增，登录与 me 的 user 亦含此字段，不暴露失败次数）。
- assignees：返回 ACTIVE 工程师/管理员的 id、name、unresolvedCount、pendingConfirmationCount 与统计时点 asOf，按姓名排序；两个计数查询正式工单表；员工角色不展示分派入口。
- 成员写操作返回 resourceId（目标成员 ID）；角色、末位管理员和工单交接规则均由服务端在事务内校验。
- 邀请：正文 name（去空格后 1～80 字符）、email、role；成功 201，响应头 Location 为 `/api/v1/users?resourceId=<新成员ID>`；邮箱已存在返回 409 EMAIL_EXISTS；新成员状态为 INVITED，须完成激活才能登录。
- 修改：正文 name、role 至少提供一个，否则 400；修改角色后该成员全部会话立即失效；最后一名在职管理员不能改角色（409 LAST_ADMIN）；工程师或管理员降为员工且有未结束负责单返回 409 MEMBER_HAS_OPEN_TICKETS。
- 停用：正文 reason 必填（去空格后 5～500 字符，用于审计）；已停用返回 409 INVALID_STATE；最后一名在职管理员不能停用（409 LAST_ADMIN）；停用后该成员会话立即失效，邀请与重置链接全部作废。
- 解锁：无正文；锁定最长 15 分钟自动解除，本接口用于提前解锁；对未锁定的成员调用也返回成功（重置失败计数）。
- 重发邀请：无正文，成功 202；仅对 INVITED 成员可用，否则 409 INVALID_STATE；重发后旧邀请链接立即失效。
- 成员不存在时写操作返回 404 RESOURCE_NOT_FOUND。

补齐契约：

- assignees 读取权限为全角色，候选为 ACTIVE ENGINEER/ADMIN；不开放其他成员管理接口。
- 已确认工程师与管理员互转保留工单归属；工程师或管理员降为员工或停用时，仍有未结束负责单则返回 MEMBER_HAS_OPEN_TICKETS，先交接再操作，结束范围排除 CLOSED/REJECTED。角色变更后的会话失效及最后管理员保护继续适用。

功能链路：

- 邀请：管理员填写姓名、邮箱、角色 → 提交邀请 → 校验邮箱唯一性 → 创建待激活成员并入队邮件 → 返回受理结果 → 成员打开链接激活；受理不代表邮件送达。
- 修改或停用：管理员查询成员 → 修改资料/角色或填写停用原因 → 事务内校验权限、当前成员状态、最后管理员及未结工单保护 → 保存并执行必要的会话失效；状态冲突刷新后由用户确认。
- 解锁：管理员选择被锁成员 → 提交操作提交 → 清除锁定 → 成员重新登录。
- 重发邀请：管理员选择待激活成员 → 校验权限与当前状态 → 使旧链接失效并入队新邮件 → 返回受理结果。
- 候选读取：具备读取权限的用户请求候选 → 服务端筛选有效工程师和管理员并统计负载 → 展示单选列表；创建或分派时仍须重新校验目标。

### M02 内部提单与统一类型（补齐已接入，待运行验收）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets | 创建工单 | 全角色 |
| GET | /api/v1/ticket-types | 可选类型；view=MANAGE 管理列表 | 默认全角色；MANAGE 仅处理角色 |
| POST | /api/v1/ticket-types | 创建类型 | 工程师、管理员 |
| PATCH | /api/v1/ticket-types/{id} | 编辑类型名称和说明 | 创建者工程师、管理员 |
| DELETE | /api/v1/ticket-types/{id} | 从新提单选项移除 | 创建者工程师、管理员 |

- 创建正文：title、description、impactScope 为非空字符串；businessTypeId 为有效类型 UUID；priority 可选 P1～P4，省略 P3；assigneeId 可选单个 ACTIVE ENGINEER/ADMIN 的 UUID，省略或 null 不指定。提单人取会话，不接受 requesterId/status。正文保持纯文本，总限制 256 KiB。
- 创建成功 HTTP 201，data 为 id、number。类型或负责人资格无效返回 400 VALIDATION_FAILED，定位 businessTypeId/assigneeId，整个事务回滚。
- 新建状态始终 NEW、version=0；可选负责人不代表受理，负责人须主动 claim。初次指定同事务保存分派审计及 assignments 通知，排除操作者；自提自接无通知，不指定无新建通知。201 不代表邮件送达。
- 创建只接受统一 businessTypeId，不接受自由文本 businessType。结果未知时保留输入，先到“我的工单”核对编号和内容，再决定是否重新提交。
- GET 类型 data.items 默认含 id/name/可选 description；MANAGE 另含 version/canManage，仅返回有效类型。创建/改名需非空 name；创建 HTTP 201 返回 id/name，改名/移除 HTTP 200 返回 resourceId。越权 403，不存在或已移除 404。
- 类型移除保留记录与历史关联；改名后历史展示读取现名。旧工单类型按精确文本去重迁入，creatorId 为空的初始类型仅管理员可管理；没有旧数据不预置类型。
- 类型列表增加可选 description，纯文本、去首尾空白后最多 500 字。创建和更新正文增加选填 description：创建省略表示无说明，更新省略保留，显式空串清除，null 或非字符串拒绝；更新仍需 name。无说明时列表省略此字段。

功能链路：读取统一类型和候选 → 填写表单、确认可选负责人和优先级 → 创建并显示编号/详情入口 → 逐个复用 M04 上传已选附件。每个文件独立请求，工单已成功时只重试未成功附件，不重新建单；未知上传保留原文件，先刷新附件列表核对。文件只在当前页保留。创建前可主动使用 M09 类型建议和 M10 处理人推荐，采用后仍由人工提交；具体契约见对应模块。

### M03 工单处理（管理员处理及拒绝已接入，待运行验收）

所有动作使用 POST `/api/v1/tickets/{id}/{action}`，成功 HTTP 200 返回 resourceId。权限不足 403、不可见 404、状态不符 409 INVALID_STATE。服务端锁定当前工单后校验并变更，同一事务保存必要操作历史和通知任务。

| action | 正文 | 状态与权限 |
| --- | --- | --- |
| claim | 无 | ACTIVE 工程师/管理员受理无主或本人负责的 NEW，设本人负责并进 IN_PROGRESS |
| assignments | assigneeId | 管理员；NEW/IN_PROGRESS/ON_HOLD，目标有效工程师/管理员 |
| transfers | assigneeId | 当前负责处理人或管理员；状态同 assignments，目标须不同于当前负责人 |
| suspensions | reason | 本人负责处理人，IN_PROGRESS → ON_HOLD |
| resume | 无 | 本人负责处理人，ON_HOLD → IN_PROGRESS |
| resolutions | summary | 本人负责处理人，IN_PROGRESS → RESOLVED，记录真实 resolvedAt |
| close | reason（提单人可省略） | RESOLVED；提单人或处理角色，代确认需原因 |
| reopen | reason | RESOLVED 或关闭后 7 日内；提单人、当前负责处理人或管理员 |
| priority-changes | priority、reason | 管理员；REJECTED 禁止，priority 为 P1～P4 |
| rejections | reason | 当前负责工程师/管理员，NEW/IN_PROGRESS/ON_HOLD → REJECTED |

reason/summary 均为去首尾空白后的非空字符串；有正文的接口拒绝未知字段，无正文动作拒绝非空 JSON。管理员处理他人单前先转派给自己，不能通过 claim 抢占已指定单。重开时复核负责人资格，无效则清空；清空本次 resolvedAt，下一次解决前不计入解决耗时样本。

拒绝保留负责人，写入公开 rejectionReason/rejectedAt 及 TICKET_REJECTIONS 审计，不写 resolvedAt、不入邮件队列。拒绝后新流转、回复、备注和上传全部禁止，不能关闭或重开；读取及原附件下载继续按权限。仍有需求则新建工单。

工程师与管理员互转保留归属；降为员工或停用前有 NEW/IN_PROGRESS/ON_HOLD/RESOLVED 负责单则要求先交接，CLOSED/REJECTED 不阻止降级。最后管理员保护和角色变化撤销全部会话继续适用。

功能链路：有权用户读取详情 → 主动确认操作 → 事务内校验当前角色、负责人和状态 → 同事务保存状态、业务操作记录及必要通知 → 刷新详情；结果未知先刷新记录核对。

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
- 回复/备注成功 HTTP 200，data 为 resourceId（工单 ID）；追加记录和工单更新同事务提交，不改变状态、负责人或轮次起点。
- NEW、IN_PROGRESS、ON_HOLD、RESOLVED 均允许追加；CLOSED/REJECTED 返回 409 INVALID_STATE，提示新建工单。公开回复允许提单人、工程师、管理员；工程师、管理员可在全部可见工单追加，不限负责人。员工仅能回复本人提单，不能添加内部备注（403 FORBIDDEN）。回复、工单版本、审计、业务记录同事务提交，回复与关闭通过同一工单行锁串行。
- 两个 GET 均支持 Query `page`（默认 1，整数且 ≥1），固定 pageSize=20，按 createdAt、id 升序；data 为 `items`、`page`、`pageSize`、`total`。非法 page 为 400 INVALID_QUERY；超出末页返回空 items 与实际 total。GET 不更新工单、操作记录或会话活动时间。
- GET replies 合并公开回复与内部备注，每项为 `id`、`content`、`visibility`（PUBLIC/INTERNAL）、`authorId`、`authorName`、`createdAt`。员工只收到 PUBLIC 项，分页和 total 均先过滤；工程师、管理员可读取全部。姓名来自当前成员姓名。
- GET operations 返回已有 M03 流转及本阶段新增回复/备注动作，每项为 `id`、`action`、`actorId`、`actorName`、`createdAt`、`details`。action 沿用审计编码：TICKET_CLAIM、TICKET_ASSIGNMENTS、TICKET_TRANSFERS、TICKET_SUSPENSIONS、TICKET_RESUME、TICKET_RESOLUTIONS、TICKET_CLOSE、TICKET_REOPEN、TICKET_PRIORITY_CHANGES；新增 TICKET_REPLY、TICKET_INTERNAL_NOTE。不返回成员审计或未知类型审计。
- 员工 operations 在查询时排除 TICKET_INTERNAL_NOTE。回复/备注的 details 只含 replyId，不复制正文；流转的 details 含 before/after，字段只允许 status、assigneeId、priority、summary（公开解决摘要）、closedAt、cycleStartedAt。仅工程师、管理员在流转 details 中可另读 reason；员工不收到原因、内部审计字段或原始审计载荷。
- 不存在或员工不可见的工单统一 404 RESOURCE_NOT_FOUND；未登录/失效 401 SESSION_EXPIRED，CSRF 错误 403 CSRF_INVALID，其余沿用通用错误。解决时间及简单统计口径见 M07。
- 详情页接入回复/操作记录独立分页、公开回复及员工不可选的内部备注。正文按纯文本展示，内部备注明确标识；已关闭单展示限制与新建入口。发表成功刷新详情及记录，并显示最新页；结果不确定时保留正文，先刷新记录核对，确认后再决定是否提交。与 M03 写操作互相禁用，避免同页并行提交。
- 附件细则已由用户确认。POST attachments 为 multipart/form-data，是 JSON 正文约定的例外；必须且仅提供一个文件 part `file` 和一个文本字段 `visibility`（PUBLIC/INTERNAL），重复/未知字段或多文件返回 400 VALIDATION_FAILED。单文件 1～10485760 字节（10 MiB，含上限），空文件 400、超限 413 REQUEST_TOO_LARGE；请求总大小额外允许 64 KiB 表单开销，其余 JSON 大小规则不变。不限制扩展名，不做预览或执行。
- 附件上传成功 HTTP 200，返回 resourceId（工单 ID）；文件元数据、工单更新和操作历史同事务提交，不改变状态、负责人或轮次。仍校验文件大小、可见性和当前工单状态。
- 上传状态与范围同公开回复；员工仅能给本人提单上传 PUBLIC，INTERNAL 为 403 FORBIDDEN。工程师、管理员可为全部可见工单上传公开或内部附件，不限负责人。CLOSED/REJECTED 禁止上传（409 INVALID_STATE），但保留已有附件下载能力。
- GET attachments 使用 page（默认1，≥1整数）、固定 pageSize=20、createdAt/id 升序，返回 items/page/pageSize/total；每项为 id、fileName、size（字节）、visibility、uploaderId、uploaderName、createdAt。员工的内部项在查询和计数前排除；不返回路径、哈希或账号凭据。非法 page 为 400 INVALID_QUERY，不存在/不可见工单为 404 RESOURCE_NOT_FOUND。
- 下载成功 HTTP 200，返回原文件二进制，不使用统一 JSON 成功包装；Content-Type=application/octet-stream，Content-Disposition=attachment（UTF-8 安全原名），Content-Length 为字节数，Cache-Control=no-store、X-Content-Type-Options=nosniff。员工只能下载本人提单的公开附件；内部附件对员工、他人工单附件对员工、不存在附件统一 404 RESOURCE_NOT_FOUND。失效会话 401，存储文件缺失/异常 503 DEPENDENCY_UNAVAILABLE，错误仍用统一 JSON。
- 上传增加 TICKET_ATTACHMENT / TICKET_INTERNAL_ATTACHMENT 操作，details 只含 attachmentId、fileName、size；员工的内部上传操作在分页/计数前过滤。文件名仅用于安全处理后的下载/展示，物理文件用随机 UUID 保存。成功附件不自动清理；事务回滚清理本次文件，进程异常/提交结果未知的残留保留人工核对，不提供暂存或删除接口。
- 前端附件区独立分页、按权限选择公开/内部、文件大小校验及鉴权下载；提交中锁定选择，状态冲突保留文件及错误并刷新详情。结果不确定时先核对附件列表，再决定是否重新上传。上传与回复/流转互相禁用；关闭后隐藏上传入口，已有可见附件仍可下载。

补齐契约：

- 配合 M03 拒绝状态，REJECTED 禁止回复、内部备注和上传，返回 409 INVALID_STATE，既有可见记录、附件列表和下载继续有效；不新增附件删除、预览或回复编辑接口。此状态联动已接入。
- 已确认不增加评论或附件删除接口，也不新增负责人修改原始 description 的接口；原始描述继续从详情只读获取，后续补充或纠错使用公开 replies，不扩展现有正文和操作权限。

功能链路：

- 沟通：打开可见工单 → 读取回复及操作记录 → 选择公开回复或有权限的内部备注 → 提交文字 → 校验身份及可写状态 → 保存并刷新；员工仅能操作本人单的公开内容。
- 补充原始问题：负责人查看原始描述 → 在公开回复说明补充或纠正信息 → 按回复权限、事务规则保存 → 原始描述与补充记录共同保留；不覆盖原文、不删除既有沟通。
- 附件：选择文件与允许的可见性 → 检查大小 → 携业务参数上传 → 保存元数据和操作记录 → 按权限列出 → 鉴权下载；结果未知则先刷新附件列表核对，明确失败按 M04 规则处理。
- 只读：CLOSED 或 REJECTED → 隐藏写入口并由服务端拒绝新写入 → 仍按权限读取既有记录和下载附件，内部内容仍对员工隐藏。

### M05 列表与检索（补齐已接入，待运行验收）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/tickets | 分页及组合筛选 | 按视图权限 |
| GET | /api/v1/tickets/{id} | 工单详情 | 员工仅本人，处理角色全部 |
| GET | /api/v1/tickets/filter-options | 历史及当前类型、人员筛选选项 | 全角色，员工不返回人员 |

- view=MINE（默认，本人提交）/ASSIGNED（本人负责）/UNASSIGNED（无负责人）/ALL；后三种只允许工程师和管理员。page 默认 1，须正整数，固定 20 条，按 createdAt/id 倒序，返回 items/page/pageSize/total。
- 筛选：status 含 NEW/IN_PROGRESS/ON_HOLD/RESOLVED/CLOSED/REJECTED；businessTypeId 为稳定 UUID；q 为去空白后 1～100 字符，按编号/标题/描述不区分大小写的字面子串查询。保留旧 businessType 文本筛选兼容，前端使用 ID。
- lifecycle=OPEN/ENDED/ALL；ASSIGNED 省略默认 OPEN，其余默认 ALL。OPEN 为 NEW/IN_PROGRESS/ON_HOLD/RESOLVED，ENDED 为 CLOSED/REJECTED；所有筛选与视图、可见范围及精确状态取交集。
- createdFrom/createdTo 均可省略，格式 YYYY-MM-DD；按 Asia/Shanghai 完整自然日，起日含、止日次日零点不含。日期非法/倒置、UUID、优先级或枚举非法均为 400 INVALID_QUERY。priority 可选 P1～P4。
- requesterId/assigneeId 精确 UUID 筛选，仅工程师/管理员可用，员工传入返回 403；可查历史成员，不以筛选扩大权限。
- filter-options 的 businessTypes 为 id/name/active：有效项及可见历史关联类型；仅处理角色额外收到可见工单关联的 requesters/assignees（id/name），不返回邮箱、技能。已移除类型仍可筛选。
- 列表和详情使用 TicketVO：id/number/title/description/businessType（现名）/businessTypeId/impactScope、提单人与负责人 ID/姓名、status/priority/version/createdAt/resolutionSummary/resolvedAt/closedAt/cycleStartedAt/rejectionReason/rejectedAt。无负责人及未发生时间为 null；不再返回 sla。GET 无业务写入及活动续期副作用。

功能链路：读取允许的筛选选项 → 选择视图及组合条件 → 服务端校验并按权限分页 → 打开详情取当前版本。待办可切换全部/已结束，不丢失历史；拒绝单只读展示原因及时间。

### M06 邮件通知（已实现，无独立前端接口）

邀请、重发邀请、重置申请继续调用 M01 既有接口写入任务；工单通知由既有 M03/M04 写接口在同一事务内入队，不新增通知查询、投递或重试接口，不改变工单请求/响应字段。后台在显式启用且配置就绪后投递和重试，接口成功不等于收件箱已收到。

- `assignments`、`transfers` 通知提单人及操作后的新负责人；公开 `replies`、`resolutions`、`close`、`reopen` 通知提单人及操作后的当前负责人。收件人去重、排除操作者，入队时仅保留 ACTIVE 且可见的成员；发送前再校验账号状态和工单可见性。负责人为空时只处理提单人候选。
- 当前新建、claim、suspensions、resume、priority-changes、内部备注、附件不通知。创建时指定负责人的例外：M02 创建时指定负责人按 assignments 通知，排除提单人后仅通知新负责人，自提自接不入队；无负责人创建仍不通知。业务校验失败、版本冲突、事务回滚不入队；成功重复状态变更不重复入队。任务入队失败沿用统一 503，并回滚业务、审计及操作记录，不返回虚假的成功结果。
- 中文纯文本工单邮件只含编号、动作、`/tickets/{id}` 登录鉴权详情链接；不含标题、描述、回复正文、原因、内部备注、附件或账号令牌。
- 复用 notification_task、领取锁、120 秒租约、4 次尝试及 60/300/900 秒退避；发送前无权访问的任务结束为 FAILED，最后一次崩溃后租约耗尽为 TIMEOUT。SMTP 接受后回写失败可能重复发送，不保证恰好一次。
- 邮件按业务入队后由后台调度投递，SMTP 使用 Spring Mail 或已保存的 M14 连接；未配置或投递失败保留任务并按既定规则重试。

M14 的 GET/PATCH /api/v1/settings 及管理员设置页已接入数据库 SMTP 配置；保存后用于后续业务投递，不发送测试邮件，详见 M14。

补齐契约：

- 配合 M02 初次指定负责人复用 assignments 入队，排除操作者，自提自接不产生任务；该入口联动已接入。
- M03 拒绝动作明确不发邮件，不向通知动作白名单加入 rejections；拒绝原因仅在系统内按权限展示。

功能链路：

- 通知：授权业务动作 → 同事务保存业务、审计及通知任务 → 返回受理成功 → 后台在配置允许时领取 → 复核收件人状态及工单权限 → 发送 → 回写结果；失败按既有间隔重试，入队失败回滚业务，投递失败不撤销业务。
- 无通知：操作者被排除后无收件人，或动作属于明确不通知范围（含拒绝） → 业务正常提交 → 不生成邮件任务。

### M07 简单统计（与 M13 同接口，已接入，待运行验收）

GET `/api/v1/dashboard/metrics` 仅管理员可用。可选 createdFrom/createdTo，日期规则同 M05；所有聚合使用同一创建日期范围和数据库一致读取视图。非法日期 400 INVALID_QUERY，角色不足 403。

- data：asOf、totalCount、countsByStatus（状态→数量，含零值及 REJECTED）、countsByBusinessType（id/name/count）、unassignedCount、assigneeLoads（id/name/openCount）、averageResolutionSeconds、resolutionSampleCount。
- 人员负载与未分派数量只计 NEW/IN_PROGRESS/ON_HOLD/RESOLVED。已拒绝不计负载或解决样本；业务类型按稳定 ID 聚合并读取现名，保留历史关联。
- 平均解决秒数仅使用当前 RESOLVED/CLOSED 且 resolvedAt≥createdAt 的有效样本，逐单取最近 resolvedAt-createdAt；无样本均值为 null、样本数 0。重开未再次解决不参与，不用更新时间补造历史解决时间。
- 固定 SLA 目标、超时、达标率已从列表/详情响应和页面撤下；历史字段保留，不修改旧迁移或删除历史数据。优先级继续表示紧急程度。

功能链路：管理员选择创建日期 → 查询同范围统计 → 展示数量、分布、负载和均值/样本数 → 手动刷新。空数据与失败分别显示，未返回结果不展示占位统计。

### M08 文本知识库（附件补齐已接入，待运行验收）

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
- 知识写入使用会话与 CSRF。创建 HTTP 201，其余 HTTP 200，data 为 {resourceId}。编辑正文在 JSON 提供读取时的 version；发布/下架无正文，按当前状态处理。正文、附件和发布操作锁定同一文章；状态不符 409 INVALID_STATE，正文过期 409 VERSION_CONFLICT，越权 403，非法查询 400 INVALID_QUERY。读取不写入或续期。

补齐契约：

- 已确认保留现有作者工程师草稿、管理员发布/下架及已发布只读规则；正文仍为纯文本。知识附件随文章授权，不复用工单 ID 或工单可见性直接授权文件。
- 分类继续自由文本，不因 M02 业务类型改统一下拉而自动改造知识分类；知识附件不进入 AI 输入，遵守项目附件隔离边界。

知识附件接口（已接入，待运行验收）：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/knowledge-articles/{id}/attachments | 上传草稿附件 | 原作者工程师，仅本人 DRAFT |
| GET | /api/v1/knowledge-articles/{id}/attachments | 查询未移除附件 | 随文章读取权限 |
| GET | /api/v1/knowledge-attachments/{attachmentId} | 鉴权下载未移除附件 | 随文章读取权限 |
| DELETE | /api/v1/knowledge-articles/{id}/attachments/{attachmentId} | 草稿中逻辑移除附件 | 原作者工程师，仅本人 DRAFT |

- 上传仅接受 multipart 的单个 file 字段，不接受 visibility；大小 1～104857600 字节（100 MiB，含上限），空文件或格式非法 400 VALIDATION_FAILED、超限 413 REQUEST_TOO_LARGE。不限制扩展名，不提供预览或执行；工单附件仍限制 10 MiB，不能因放宽知识上传而一并放开。
- 知识附件上传/逻辑移除成功返回 resourceId（文章 ID），递增文章 version、updatedAt，不自动发布。与正文、发布/下架通过文章行锁协调；重复移除同一附件直接成功，物理文件保留。
- 已发布文章禁止附件变更，409 INVALID_STATE；管理员可下架并读取草稿附件，但无作者工程师资格时不允许上传或移除。不可见文章、附件不存在、归属不匹配或已移除附件统一 404；对可见草稿无编辑权的写入为 403。列表仅返回未移除项，按 createdAt/id 升序、固定每页 20，包含 id/fileName/size/uploaderId/uploaderName/createdAt，不返回存储路径或哈希。
- 发布后任何登录角色可按文章权限列出和下载；下架后立即回到作者工程师/管理员可读范围。下载沿用 M04 的 application/octet-stream、安全 Content-Disposition、no-store/nosniff 和二进制响应，不沿用工单的权限条件。草稿移除仅记录 removedAt/removedBy，保留文件和历史元数据，后续普通列表/下载不返回已移除项；不新增恢复、物理删除或历史文件下载入口。
- 附件上传与移除在文章锁内检查作者及草稿状态；重复移除已移除附件直接成功。上传结果未知时先刷新附件列表核对，再决定是否重新上传。新文件失败补偿沿用既有回滚清理，不清理已成功或逻辑移除文件。

功能链路：

- 创作：工程师创建本人草稿 → 填写纯文本标题、正文、分类 → 保存并取得版本 → 管理员检查后发布 → 全角色按关键词和分类检索并阅读。
- 修订：管理员下架 → 文章回到 DRAFT，员工不再可读 → 原作者工程师修改并保存 → 管理员重新发布 → 展示最新已发布内容；版本冲突先刷新并由用户确认。
- 附件上传（已接入，待运行验收）：作者工程师打开本人草稿 → 选择不超过 100 MiB 的文件 → 校验并携文章业务参数上传 → 保存附件并更新文章版本 → 管理员检查文章及附件后发布 → 全角色按文章权限下载。
- 附件修订（已接入，待运行验收）：已发布文章 → 管理员下架 → 仅作者工程师/管理员可读 → 作者工程师上传或逻辑移除 → 保存更新版本 → 管理员重新发布；被移除文件保留存储但不再可下载。
- 附件失败重试（已接入，待运行验收）：上传失败或未知 → 保留 File、文章原版本和键 → 按原请求确认结果 → 明确冲突时刷新并由用户确认 → 成功后才进入发布流程，不把本地待上传文件列为已发布附件。

### M09 AI 业务类型建议（提单页与详情页已接入）

当前接口覆盖提单前建议、详情页建议与人工确认；共用已有类型和模型计算逻辑。

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/ai/classification-suggestions | 提单前生成类型及优先级建议 | 全部已登录有效成员 |
| POST | /api/v1/tickets/{id}/ai/classification-suggestions | 生成已有业务类型及优先级建议 | 同下述类型确认角色/状态范围 |
| POST | /api/v1/tickets/{id}/classifications | 人工确认业务类型，不写优先级 | NEW 提单人/负责人/管理员；处理中或挂起的负责人/管理员 |

参数要点：

- 建议接口返回的均为建议值，不直接生效；分类即 businessTypeId，不再增加平行分类或标签。
- classifications 仅含确认后的 businessTypeId，不能写入 priority；优先级建议由管理员通过 M03 priority-changes 确认并填写原因，其他角色不得绕过。类型只能选已有可用项，AI 不自动创建类型。
- AI 不可用或已关闭（M14）时不影响人工提单与处理。

已实现契约：

- 提单前建议正文仅 title（去首尾空白后 1～1000 字符）、description（1～60000 字符）、impactScope（可省略或空字符串，最长 4000 字符）；显式 null 或非字符串非法。不接受 businessTypeId、自定义候选或其他字段。复用会话及 CSRF，不需要临时工单、版本头或幂等 Key。响应沿用下述三字段；无可用类型返回三个 null。仅记录已有 AI 调用状态，不创建工单、操作记录、附件或邮件任务。
- 创建页建议独立加载和报错；点击“采用此类型”才填入类型，不修改初始优先级。标题、描述、影响范围或手选类型变化后旧建议及在途结果失效；提交成功或离开页面后忽略迟到结果。AI 等待或失败不阻止人工提单。正式建单仍由 M02 校验全部必填字段和类型有效性；失效类型刷新选项并要求重新选择，不自动替换。
- 已确认取消标签及独立分类字段；M09 复用 M02 业务类型选项，管理权限不因 AI 请求扩大。NEW 限提单人、当前负责人或管理员，IN_PROGRESS/ON_HOLD 限当前负责人或管理员；RESOLVED/CLOSED/REJECTED 禁止类型写入及本入口建议请求。操作角色和目标状态在服务端检查，不仅隐藏按钮。
- classifications 正文仅 businessTypeId（必填 UUID），事务内检查权限、状态及类型有效性，更新类型和业务操作记录，不改变负责人、优先级或状态，不触发邮件。成功 HTTP 200 返回 resourceId；相同类型重复确认直接成功且不新增记录。类型失效或字段非法 400、不可见 404、无权限 403、状态不允许 409。
- classification-suggestions 不接受客户端工单正文或自定义类型集合，由 Java 按路径工单取公开问题字段与可用类型；本接口不写业务字段，建议响应含 businessTypeId、reason、suggestedPriority，前两者可为空表示无可用建议，优先级建议仅 P1～P4 或 null；前端明确“建议尚未生效”。AI 重试与错误遵循 M14 共用约定，本版无每日次数额度，不用 suggestions 接口代替类型管理。
- AI 输入仅使用经授权的工单公开问题信息及可用类型选项，不读内部备注或任何附件。输出候选类型与简短依据、可选优先级建议；Python 校验 JSON 结构、候选枚举及字段关系，Java 反序列化并复核当前业务权限、状态、配置和类型有效性，建议不更新工单或版本。

功能链路（提单页与详情页已实现）：

- 创建页填写标题和描述 → 手动生成建议 → 查看并采用已有类型 → 补全必填信息 → M02 校验并建单；AI 失败可继续人工选择提交。
- 建单后打开有权操作的工单 → 手动生成类型/优先级建议 → Java 校验可见性并提供可用类型和当前模型连接 → Python 计算并校验输出 → Java 复核当前业务有效性 → 人工确认已有类型 → 事务内校验权限、状态及类型有效性后保存；优先级单独由管理员填写原因经 M03 调整。
- AI 不可用 → 显示说明并保留当前工单 → 有权限者仍可人工选择业务类型 → 同样通过分类确认校验，不自动创建标签或类型。
- 状态或角色变化：页面生成建议后工单已被受理、解决或负责人变化 → 提交时重新检查权限、状态和类型有效性 → 不满足则拒绝并刷新，建议不会自动覆盖新数据。

### M10 处理人推荐

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/assignee-recommendations | 返回最多 3 名候选处理人及推荐依据 | 工程师、管理员 |
| POST | /api/v1/ai/assignee-recommendations | 提单前推荐处理人 | 全部已登录有效成员 |

参数要点：

- 候选为有效处理人（ENGINEER/ADMIN，与 M03 调整同步），推荐结合已解决单经验与当前负载，并参考本人技能描述。
- 推荐不自动分派；创建前通过 M02 assigneeId 人工确认，建单后管理员分派用 M03 assignments，负责人按既有权限转派用 transfers。
- 负载数据同 GET /api/v1/users/assignees。

已实现契约：

- `POST /api/v1/ai/assignee-recommendations` 需现有会话与 CSRF。正文为 title、description、businessTypeId、impactScope，均必填；去首尾空白后的文本长度分别为 1～1000、1～60000、1～4000 字符，类型为有效业务类型 UUID。不接受附件、内部备注或客户端自定候选名单，不依赖工单 ID。详情入口无正文，由 Java 读取工单公开问题；全部状态可计算，计算不授予对应状态的分派权限。
- Java 校验输入、类型有效性并取得 ACTIVE ENGINEER/ADMIN 候选，再调用 Python 计算。成功 HTTP 200 返回 data.items，最多 3 项，每项 assigneeId、name、reason；无候选或无匹配返回空数组。Python 校验候选枚举、唯一性及最多 200 字符非空理由，Java 返回前重验配置、会话及当前候选资格并补充当前姓名，过滤已失效人员。历史经验仅为候选人按业务类型汇总的当前 RESOLVED/CLOSED 工单数量；未结束负载包含 NEW/IN_PROGRESS/ON_HOLD/RESOLVED，与候选查询一致。仅传汇总数量和技能，不提供历史工单正文、账号邮箱或无权读取的记录。历史归属使用当前保留的 assigneeId；业务类型按稳定 ID 聚合。本版无每日次数额度，超时及调用错误见 M14。
- 已确认将候选人自行维护的 skillsDescription 作为历史经验和负载之外的补充依据，允许为空；由 Java 从有效候选中读取并作为不可信文本提供，不接受浏览器指定他人的技能，不从技能文本执行指令。前后推荐共用此依据，响应仍只返回候选与简短理由，不增加全角色读取成员技能详情的能力。
- 推荐请求只产生建议，不创建工单、不指定负责人、不触发分派通知。用户手动确认后使用 M02 assigneeId；表单改变后原建议应提示已过期，创建仍以当前表单和服务端最终校验为准。无候选、AI 关闭或失败不阻止人工提单。
- 建单后推荐沿用既有设计的工单路径与工程师/管理员调用范围，但“取得推荐”不授予分派权限：管理员可按 M03 分派，负责人只能执行已授权的转派动作，其他工程师不能据此改派。

功能链路：

- 创建前：全角色填写表单 → 手动请求推荐 → Java 校验并提供授权候选 → Python 返回建议 → Java 过滤候选与可展示理由 → 提单人确认 → M02 创建时校验并指定。
- 创建后：工程师或管理员打开可见工单 → 请求推荐 → 查看候选与理由 → 有权操作者按 M03 分派或转派 → 校验当前状态及目标 → 生效；无操作权限仅能查看建议。
- 降级：无候选、AI 关闭或失败 → 显示说明 → 保留当前表单或工单 → 人工选择或按允许的流程不指定提交，不自动选择替代人。

### M11 相似问题检索

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/similar-issues | 返回最多 5 条相似来源 | 工程师、管理员 |

参数要点：

- 结果来自已解决工单与已发布知识，仅含调用角色可见的内容。
- 每条结果标注真实来源（工单编号或知识 ID）。

已实现契约：

- 请求使用路径中的当前工单，无需正文；Java 验证工程师/管理员及工单可见性，读取标题、原始描述、业务类型等公开问题字段；不使用浏览器提供的候选语料。接口只检索，不改变状态、负责人、版本或业务操作记录，不发送邮件。
- 候选来源仅当前 RESOLVED/CLOSED 工单的可见标题、原始描述和公开解决摘要，以及当前 PUBLISHED 知识的标题和正文；排除当前工单、REJECTED、未解决单、知识草稿、内部备注及所有附件。Java 从数据库查询授权候选，再由 Python 计算相关性，不把全库交给模型自行查。
- 成功 HTTP 200 返回 data.items，0～5 项，含 sourceType（TICKET/KNOWLEDGE）、sourceId、number（仅工单，知识为 null）、title、excerpt、reason。标识、编号、标题和最多 240 字符原文摘要由 Java 组装，工单摘要优先取公开解决摘要；模型仅选择候选并给出最多 200 字符理由。链接由前端按类型和 ID 构造，不采纳模型链接或摘要。返回前重验会话、配置及来源资格，来源已下架、重开或正文/标题变化则剔除，不凑足结果；重复或伪造候选返回 AI_INVALID_RESPONSE。
- 召回使用当前标题/描述前 6000 字符，提取最多 24 个英文/数字词或中文相邻双字；PostgreSQL 按命中的词数排序，取最多 10 个真实来源。当前问题向模型提供标题前 1000、描述前 4000 字符；知识提供命中附近最多 2000 字符，工单提供问题附近 1200 字符及公开解决摘要前 800 字符。纯同义改写且无共同词片段可能无命中，可使用人工检索；本版未引入向量检索。
- 无匹配返回 200 和空 items；AI 关闭或计算失败显示不可用并允许使用 M05/M08 的人工关键词检索，不把依赖失败伪装为空结果或成功检索。具体 AI 错误和主动重试遵循 M14 共用约定，本版无每日次数额度；依赖故障不修改当前工单。

功能链路：

- 工程师或管理员打开可见工单 → 主动检索 → Java 查询已授权的解决单/已发布知识候选 → Python 判断相关性 → Java 校验真实来源、状态和权限 → 返回最多 5 项 → 用户打开真实详情核对。
- 无命中 → 展示暂无相似来源 → 继续人工检索或直接处理；服务失败 → 显示错误及重试/人工入口，两种结果明确区分。
- 来源变化：计算期间知识被下架或源工单重开 → 返回前重新检查 → 移除无效来源；用户后续点击仍通过真实详情接口校验，不靠缓存摘要绕过权限。

### M12 AI 回复草稿

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/tickets/{id}/ai/reply-drafts | 基于当前问题与相似检索材料生成回复草稿 | 工程师、管理员 |

参数要点：

- 草稿不自动发送；人工核对修改后通过 M04 的 replies 发表，仅在用户当前页面保留。
- AI 不可用时不影响人工直接回复。

已实现契约：

- 工程师/管理员可对可见 NEW/IN_PROGRESS/ON_HOLD/RESOLVED 生成草稿，不要求本人负责；CLOSED/REJECTED 返回 409 INVALID_STATE，非处理角色 403，工单不存在/不可见 404。请求由 Java 读取路径工单和 M11 授权候选，不接受客户端注入他人工单正文、系统提示或候选来源。
- 生成不写工单、回复、审计或版本，不触发通知；返回 data 的 content、sources（sourceType/sourceId/number/title）、basedOnVersion 和 hasReferenceSources。sources 只允许真实授权来源，最多沿用 M11 的 5 项，不包含内部备注或任何附件；Java 返回前再次验证工单状态和来源可见性，过时生成结果返回 409 INVALID_STATE。
- 草稿仅当前页面，服务端不保存业务草稿箱或增加草稿查询/恢复接口。用户编辑后经 M04 replies 发送，使用会话与 CSRF；不得把“生成草稿”视为已经回复或首次人工响应。生成期间工单变化则提示重新核对，不能自动拿新版本覆盖提交。
- AI 关闭/超时/失败明确提示并保留人工回复入口，不发空草稿冒充成功。已确认无相似来源时基于当前问题生成补充信息询问或一般建议，sources=[] 且 hasReferenceSources=false，页面标注“无参考来源”；有来源时为 true。模型被要求不编造引用和已验证结论，用户仍须核实。重新点击生成属于新的 AI 请求，不自动重试。

- M11 召回和 M12 生成最多各调用一次模型，均使用现有连接与超时；当前问题及授权来源由 Java 提供，Python 返回最多 2000 字符的纯文本草稿。页面手动填入公开回复后，POST replies 附带 basedOnVersion，服务端在工单行锁内比较版本，变化时返回 409 INVALID_STATE 并保留文字；普通手工回复不需该字段。

功能链路：

- 工程师或管理员打开可回复的可见工单 → 主动生成 → Java 校验角色/状态并取得公开问题与授权来源 → Python 生成 → Java 核验来源及当前状态 → 页面显示可编辑草稿和真实来源 → 用户核对、修改 → 通过 M04 公开回复发送 → 成功后才生成正式回复及相应通知。
- 已解决待确认：RESOLVED → 可生成并人工追加回复 → 状态仍 RESOLVED；已关闭/拒绝则禁止生成和发送。
- 丢失或失效：刷新/离开页面后草稿不保证保留；生成后工单关闭/拒绝或版本变化 → 发送时再次校验 → 提示冲突/只读，不自动发送；用户仍可手工复制未提交文字供核对。
- 无相似来源：M11 确认无匹配 → 使用当前问题生成一般建议或补充询问 → 展示无参考来源 → 人工核对修改后按 M04 发送；M11 服务失败则走失败提示，不冒充正常无命中。

### M13 基础看板（随 M07 接入，待运行验收）

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/dashboard/metrics | 看板统计 | 管理员 |

参数要点：

- 目标统计内容：工单总数、状态/业务类型分布、未分派量、人员未结束负载及平均解决耗时，口径统一由 M07 定义；不提供固定 SLA 目标、超时、达标率或评分。

接口契约：

- 继续使用 GET /api/v1/dashboard/metrics，仅管理员可调用；无业务写入、审计新增或会话续期副作用，员工及工程师访问返回 403。支持 createdFrom/createdTo（YYYY-MM-DD，均可省略，默认全部），与 M05 同样按 Asia/Shanghai 自然日、起日含/止日次日零点前计算；非法日期或起日晚于止日返回 400 INVALID_QUERY。数量、分布、负载和解决耗时基于同一创建日期范围，不增加解决日期筛选。
- 返回 data 的 asOf、totalCount、countsByStatus、countsByBusinessType、unassignedCount、assigneeLoads、averageResolutionSeconds、resolutionSampleCount。状态覆盖 REJECTED；类型按稳定 ID 关联现名，保留已删除类型。人员项仅 id/name/openCount，不返回邮箱或技能；历史负责人按稳定 ID 归类，不按姓名合并。
- 平均耗时及样本排除规则见 M07；无工单时总数/计数为 0，平均耗时为 null。未分派量只计未结束且 assigneeId 为空的工单；asOf 明确本次统计时点，相关聚合使用一致的读取视图，避免同次响应的总数和分布互相矛盾。

功能链路：

- 管理员进入看板 → 选择已确认的统计范围 → 调用 metrics → 展示总量、状态/类型分布、人员负载和平均解决耗时及样本数 → 手动刷新；无样本显示暂无数据，失败显示错误及重试，不使用占位数字冒充真实统计。

### M14 最小配置

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| GET | /api/v1/settings | 读取团队配置与 AI 调用状态 | 管理员 |
| PATCH | /api/v1/settings | 更新团队配置 | 管理员 |

参数要点：

- 更新正文含团队名称、邮件连接、模型连接、AI 开关。
- GET 返回上述配置及只读的 AI 调用状态。

接口契约：

| 方法 | 路径 | 用途 | 调用角色 |
| --- | --- | --- | --- |
| POST | /api/v1/settings/model-connection-tests | 使用已保存连接发送固定测试文本 | 管理员，AI 关闭时也允许 |

- GET settings 返回 id/version、teamName、smtp（host/port/username/from/security/passwordConfigured）、model（baseUrl/model/apiKeyConfigured）、aiEnabled 及 lastTest/lastCall 只读状态。SMTP 密码和模型密钥不回显，不把掩码字符串当作可回传凭据；后台状态不得包含原始密钥、工单正文、完整供应商请求或错误堆栈。
- PATCH 接受 teamName、smtp、model、aiEnabled 的局部更新，至少一项；管理员权限校验后在事务内串行保存，未提供字段保留，HTTP 200 返回 resourceId。teamName 非空、端口 1～65535，地址/安全模式和未知字段由服务端校验。模型连接不完整时不得保持或开启 AI，返回 409 INVALID_STATE。保存不触发模型请求。
- 凭据字段省略表示保留，非空值表示替换；smtp.clearPassword/model.clearApiKey=true 才表示显式清除。清除与同时提供新值互斥，空串或 null 不作为清除命令，避免掩码或空表单误覆盖。凭据保存到受访问权限保护的数据库，运行按配置版本使用；页面不改 .env，数据库未保存前可使用部署引导配置。首次局部保存以当前有效配置为基线保留省略项，已有数据库配置优先，显式清除后不回退旧环境密钥。
- 保存设置不发送测试邮件、不自动测试模型或开启 AI；保存后的邮件连接供后台业务投递使用；AI 开关必须由管理员显式选择。SMTP 参数接入现有投递器，收件权限保护继续适用，后台按有效连接处理待投递任务。模型只配置一个连接，不支持页面填写脚本或浏览器直接调用 Python。
- model-connection-tests 不接受业务正文或任意提示，只测试当前已保存的连接，返回测试状态及 testedVersion；配置缺失返回明确错误，失败不改变连接或启用状态。对同一管理员限制每 1 秒一次，超限 429 RATE_LIMITED/Retry-After；只记录脱敏结果，配置已变化时旧测试结果不作为新配置可用的证据。
- 本版不设每日次数、个人或团队额度，不增加额度扣减/预占表或“额度耗尽”错误。读取只显示启用、已配置及最近调用/测试状态；连接未测试或无调用时明确“未测试/暂无调用”，不能显示虚假的成功。

M09～M12 共用调用约定（业务入口随对应模块接入）：

- 纯计算建议/检索/草稿请求使用既有会话、Origin 和 CSRF，不写工单版本。人工应用业务类型、分派、回复仍各自带业务参数。页面提交中禁止重复点击，失败由用户主动重试，不自动重试外部生成；重新生成是新计算，不承诺恢复原草稿或原建议。
- 统一包裹响应及 requestId；AI 关闭返回 503 AI_DISABLED，缺配置 503 AI_NOT_CONFIGURED，输出结构/候选来源非法 503 AI_INVALID_RESPONSE，材料超限 503 AI_REQUEST_TOO_LARGE，Python/模型依赖故障 503 DEPENDENCY_UNAVAILABLE，有限等待超时 504 AI_TIMEOUT。供应商认证、限流及请求拒绝按下文独立错误码返回。M09～M12 已接入配置与权限重验，不将错误伪装成空结果。
- Java 和 Python 设置有限连接/读写/总等待并及时取消，具体传输值在部署接入时校验；只回传可公开的错误说明，不回显密钥或原始供应商异常。AI 关闭阻止后续业务调用，已在途结果返回前重验开关/权限，关闭后不自动应用结果；管理员连接测试是独立例外。

功能链路：

- 配置：管理员读取设置和版本 → 修改团队/SMTP/模型参数，按需替换或显式清除凭据 → 校验权限及配置完整性 → 事务内保存 → 返回资源 ID → 后续服务按保存配置运行，页面只显示凭据已配置状态。
- 测试：管理员保存模型连接 → 主动测试（AI 可关闭） → 发送固定测试文本 → 脱敏展示对应配置版本的结果 → 管理员决定是否开启，不自动切换开关或保存别的表单修改。
- 开关与人工降级：管理员关闭 AI → 新业务 AI 请求明确不可用 → M02/M03/M04/M05/M08 人工流程继续；管理员开启前须配置完整连接，无调用记录时显示暂无调用。

具体字段与边界：

- 未保存时 id 固定为 00000000-0000-0000-0000-000000000014、version=0，AI 默认关闭；PATCH 返回 resourceId，保存后重新 GET 取得递增的配置版本。GET 不写库、不续期会话、不连接外部服务。teamName 为 1～80 字符；smtp.host ≤253、username/from ≤320、port 为整数；security 为 SSL/STARTTLS/NONE，NONE 仅 local/test 的 loopback 可用。允许保存尚未完整的连接，但不能启用不完整的 AI。
- model.baseUrl ≤2048、model.model ≤200，地址禁止 userinfo/query/fragment，要求 HTTPS；local/test 允许 loopback HTTP。模型采用兼容 Chat Completions 的连接，baseUrl 包含 API 前缀（如 /v1），Python 追加 /chat/completions。密码和密钥最多 512 字符，拒绝纯掩码、空串、null、控制字符；省略保留，显式 clear 为 true 时落库空值且不回退部署凭据。其余字符串字段可留空表示未配置；未知字段、空对象及错误类型拒绝。
- lastTest/lastCall 为 {status,testedVersion,occurredAt,current}；没有记录分别为 NOT_TESTED/NO_CALLS，版本和时间为 null。测试开始记录 RUNNING，完成为 SUCCESS 或公开错误码；异常退出后超过 20 秒的 RUNNING 读取为 AI_TIMEOUT，配置变更后 current=false。业务 AI 尚未接入时 lastCall 保持 NO_CALLS。
- POST model-connection-tests 接受空正文或 {}，拒绝任何自定义字段；仅测试数据库已保存且完整的连接。成功 data 为 {status:"SUCCESS",testedVersion,occurredAt,current}；调用失败使用统一错误包装，同时保留对应版本的脱敏测试状态，限频在失败后仍有效。连接缺失返回 503 AI_NOT_CONFIGURED；内部服务不可达或供应商失败返回 503 DEPENDENCY_UNAVAILABLE；非法输出返回 503 AI_INVALID_RESPONSE；超时返回 504 AI_TIMEOUT。
- Python 模型请求连接/读/写/连接池超时分别为 2/5/3/2 秒，总等待 10 秒；Java → Python 连接 2 秒、请求 15 秒、含响应体总等待 16 秒，超限主动取消。两端禁止重定向；Python 响应体最多 64 KiB，Java 内部响应最多 16 KiB；无自动外部生成重试。浏览器仍使用既有 Cookie/Origin/CSRF。

## 内部接口（Java → Python，M09～M12 及 M14 模型测试）

以下接口由 Java 后端调用 Python AI 服务，浏览器不可直接访问；不适用本文档的浏览器会话和统一响应包装。M14 固定文本测试与 M09～M12 AI 接口已接入，材料范围和输出限制遵循对应模块，不可绕过 Java 权限和输出过滤。

| 方法 | 路径 | 用途 | 对应前端接口 |
| --- | --- | --- | --- |
| POST | /internal/v1/classification-suggestions | 已有业务类型与优先级建议，不含标签 | M09 classification-suggestions |
| POST | /internal/v1/assignee-recommendations | 处理人候选推荐 | M10 assignee-recommendations |
| POST | /internal/v1/similar-issues | 相似问题检索 | M11 similar-issues |
| POST | /internal/v1/reply-drafts | 回复草稿生成 | M12 reply-drafts |
| POST | /internal/v1/model-connection-tests | 固定文本连接测试，不使用工单材料 | M14 model-connection-tests |

Python 侧只做 AI 计算，不访问数据库；输入材料（授权公开问题、合法处理人及汇总经验/负载/技能、授权检索语料）由 Java 组装后传入。模型连接凭据只供服务端传输客户端认证，不作为提示词材料，不提供 SMTP 或账号凭据，不写日志或响应；测试只走固定文本的独立内部入口，不借业务提示注入。内部调用需限制为服务端访问并校验调用方，M09 输出由 Python 按动态 Pydantic 类型校验结构、候选枚举及字段关系，Java 仅反序列化并复核当前业务权限、状态、配置版本及类型当前有效性；不在 Java 重复手写输出 Schema 校验。其他 AI 模块仍按各自契约接入。

M14 内部测试正文为 {modelConnection:{baseUrl,model,apiKey}}，不含工单或 SMTP 字段；成功返回 {status:"SUCCESS"}，失败返回 {code}。Java 仅调用同机 loopback 地址，Python 以 `python -m app` 固定监听 127.0.0.1:8001、忽略代理头，并在解析正文前拒绝非本机客户端。关闭文档和 CORS；模型 API 凭据仅用于供应商 Authorization，不进入提示词、响应或日志。

M09 直接生成与结果校验：

M10 内部正文为 `{modelConnection,ticket:{title,description,impactScope,businessType},candidates:[{id,skillsDescription,openCount,resolvedCount}]}`。候选仅由 Java 从数据库组装，不含姓名、邮箱、历史正文或其他账号信息；模型凭据仅用于 HTTP Authorization。Python 返回 `{items:[{assigneeId,reason}]}`，不返回或生成姓名，Java 补充当前成员姓名。候选技能文本及工单内容均按不可信材料处理，沿用普通 Chat Completions、有限等待和严格 Pydantic 校验，不增加能力预检或自动重试。

M11 内部正文为 `{modelConnection,ticket:{title,description,businessType},sources:[{sourceKey,title,text}]}`，最多 10 个 Java 授权来源；sourceKey 为 `TICKET:UUID` 或 `KNOWLEDGE:UUID`。Python 返回 `{items:[{sourceKey,reason}]}`，最多 5 个唯一的输入候选；无匹配返回空数组。动态枚举与 Pydantic 校验拒绝伪造来源、重复、额外字段或无效理由。Java 补充当前真实元数据和原文摘要，返回前剔除已变更的来源；不接收模型提供的 URL 或摘要。

M12 内部正文为 `{modelConnection,ticket:{title,description,businessType},sources:[{sourceKey,title,text}]}`，sources 为 M11 选出的最多 5 项，也可为空。Python 只返回 `{content}`，严格校验非空、最长 2000 字符及无额外字段；Java 在返回前重验来源和工单版本。生成正文可能有误，发送前由用户核对。

- 建议生成只要求 AI 开启、模型配置完整及当前用户/工单具备业务操作条件，无需先执行连接测试。测试未执行、失败或对应旧配置版本均不阻止生成。无候选直接返回三字段 null，不调用模型。
- 管理员连接测试只发送一次固定短文本，不使用工单材料，不自动保存配置或启用 AI；测试结果是诊断信息。保留 testedVersion、occurredAt、current 和 attempt_id 对最新尝试的保护，不返回或传递 outputMode。
- 内部 classification-suggestions 正文为 {modelConnection,ticket:{title,description,impactScope},businessTypes:[{id,name,description?}]}。Python 调用普通 Chat Completions，提示词要求仅返回 JSON；无 response_format、工具调用、模式探测或自动重试。
- Python 使用 Pydantic 校验 businessTypeId（本次有效 UUID ID 或 null）、reason（最多 200 字中文说明或 null）、suggestedPriority（P1～P4 或 null），三字段必填，不允许额外字段。ID 与 reason 必须成对为 null 或成对有效，理由非空且无控制字符；重复键、非 JSON、拒答和截断直接返回 AI_INVALID_RESPONSE，不猜测或补齐结果。Java 返回前复核当前权限、状态、配置及类型有效性，人工确认后才修改业务数据。
- 供应商认证失败返回 503 AI_PROVIDER_AUTH_FAILED，请求限流返回 503 AI_PROVIDER_RATE_LIMITED，请求参数/模型/地址被拒绝返回 503 AI_PROVIDER_REQUEST_REJECTED，网络或其他供应商故障返回 503 DEPENDENCY_UNAVAILABLE；超时返回 504 AI_TIMEOUT，响应无效返回 503 AI_INVALID_RESPONSE。仅向页面和日志提供固定错误类别，不返回供应商原始正文或凭据。
- Java→Python 正文最多 128 KiB；完整模型请求最多 256 KiB，超限明确拒绝、不截断候选；建议输出预算 1024 tokens，连接测试 16 tokens，模型响应最多 64 KiB，Java 内部响应最多 16 KiB。超时边界沿用上文。
