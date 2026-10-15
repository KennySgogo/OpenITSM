# M01 前端设计说明

## 路由表

| 路径 | 页面 | 可访问角色 | 未登录时行为 |
| --- | --- | --- | --- |
| `/login` | 邮箱密码登录 | 匿名、所有角色 | 直接访问；returnTo 交给后端，成功只使用 redirectPath |
| `/activate?token=…` | 邀请激活、设置密码 | 匿名、所有角色 | 直接访问，缺少 43 字符 token 时禁用提交 |
| `/forgot-password` | 申请找回密码 | 匿名、所有角色 | 直接访问 |
| `/reset-password?token=…` | 设置新密码 | 匿名、所有角色 | 直接访问，缺 token 禁用 |
| `/admin/users` | 成员管理 | ADMIN | 跳登录并带完整 returnTo |
| `/tickets`、`/tickets/:id` | 工单占位 | 全角色；员工仅 MINE 列表视图 | 跳登录并带 returnTo；不加载业务数据 |
| `/dashboard` | 看板占位 | ADMIN | 跳登录并带 returnTo |
| `/knowledge` | 知识库占位 | 全角色 | 跳登录并带 returnTo |
| `/settings` | 配置占位 | ADMIN | 跳登录并带 returnTo |
| `/` | 默认落地 | 已登录 | 跳登录；恢复后使用后端 defaultPath |
| 其他路径 | 未找到页 | 已登录 | 跳登录并带 returnTo |

## 页面清单

| 页面 | 布局分区 | Element Plus 组件 | loading / 空态 / 错误态 |
| --- | --- | --- | --- |
| 登录 | 双栏卡片；左侧蓝色建筑几何插画，右侧品牌与表单 | Form、Input、Button、Alert | 提交锁定；原文错误置表单顶部；无注册、记住我、第三方登录 |
| 激活 | 居中卡片、状态图标、双密码表单、规则、返回登录 | Form、Input、Alert | 提交 loading；缺 token 禁用；失效联系管理员；成功原文提示并回登录 |
| 找回申请 | 居中卡片、邮箱表单、结果区 | Form、Input、Button、Result | 成功后仅展示服务端统一 message，不推断账号存在，不声称邮件已送达 |
| 重置密码 | 与激活一致的卡片、即时密码规则、双密码表单 | Form、Input、Alert | Unicode 码点 12～64、至少一个字母和数字、一致；fieldErrors 仍展示；失效可重新申请 |
| 成员管理 | 左导航、顶栏、页标题、状态页签、关键词与角色筛选、表格、固定 20 条分页、操作弹窗 | Tabs、Input、Select、Table、Avatar、Tag、Pagination、Dropdown、Dialog、Form | 真实列表 loading；空态区分无成员/无匹配；加载失败可重试；403 页内提示 |
| 应用框架 | 品牌、按角色导航、个人信息、退出、路由内容 | Avatar、Button、Alert、Result | 刷新先 me 恢复；网络失败可重试；角色不足页内提示；401 停心跳跳登录 |
| 占位/未找到 | 标题、未实现标记、中央说明卡片 | Tag | 明确“功能建设中”，没有假功能、假统计或假数据 |

## 接口映射表

| 页面动作 | 方法与路径 | 成功行为 | 错误处理 |
| --- | --- | --- | --- |
| 首次写入/恢复 token | GET `/api/v1/auth/csrf` | 获取与 Cookie 对应的 token；并发调用合并 | CSRF_INVALID 重新获取、同请求重试一次，再失败刷新 |
| 登录 | POST `/api/v1/auth/login` | 保存会话与旋转 token，原文 message，跳 redirectPath | AUTH_INVALID 原文留表单；429 倒计时 |
| 刷新恢复 | GET `/api/v1/auth/me` | 恢复用户、token、默认路径；恢复本身不算真实交互 | 401 清状态、returnTo 登录；其他错误重试 |
| 真实用户活动 | POST `/api/v1/auth/activity` | 60 秒检查，只在有未上报的真实交互时发无正文请求 | 隐藏/401 停止；429 等待 Retry-After；其他错误页内展示 |
| 退出 | POST `/api/v1/auth/logout` | 无正文；清会话回登录 | 原文提示、重试 |
| 邀请激活 | POST `/api/v1/auth/activations` | token/password/confirmPassword；原文成功信息、回登录 | 字段原文；TOKEN_INVALID 联系管理员 |
| 找回申请 | POST `/api/v1/auth/password-reset-requests` | 仅传 email；展示统一 message | 原文错误；429 禁用提交 |
| 密码重置 | POST `/api/v1/auth/password-resets` | token/password/confirmPassword；清会话、回登录 | 字段原文；TOKEN_INVALID 返回申请 |
| 列表/筛选/分页 | GET `/api/v1/users` | 仅 page/q/role/status；固定 20 条 | 页内错误、重试/重置筛选 |
| 邀请成员 | POST `/api/v1/users` | name/email/role；仅 Idempotency-Key；关闭弹窗刷新 | API 邀请规格无 If-Match，不构造版本；失败复用快照重试 |
| 改名/改角色 | PATCH `/api/v1/users/{id}` | name/role；If-Match 为列表 version；Idempotency-Key 为 UUID | 版本冲突关闭旧操作、刷新后用户重新确认 |
| 停用 | POST `/api/v1/users/{id}/disable` | 仅 ACTIVE；reason 5～500 字符；版本与幂等头 | 失效状态刷新；末位管理员、交接限制原文展示 |
| 解锁 | POST `/api/v1/users/{id}/unlock` | 仅 lockedUntil 晚于当前时间；无正文；版本与幂等头 | 原文错误，必要时刷新列表 |
| 重发邀请 | POST `/api/v1/users/{id}/invitation-resend` | 仅 INVITED；无正文；版本与幂等头；提示已受理 | 429 按 Retry-After；旧邀请将失效，提交前说明 |
| 不调用 | GET `/api/v1/users/assignees` | M02+ 分派场景，无入口 | 不属于 M01 前端 |
| 不调用 | GET `/health/live`、GET `/health/ready` | 本机运维用途 | 浏览器不调用；17 个接口中前端覆盖 14 个 |

## 错误处理映射表

| 错误码 | 前端动作 |
| --- | --- |
| VALIDATION_FAILED | 展示后端 message 与全部 fieldErrors，不覆盖原文 |
| SESSION_EXPIRED | 清本地会话与 token、停止心跳，登录页携带 returnTo，保留原文提示 |
| AUTH_INVALID | 登录表单展示原文，不重定向循环 |
| CSRF_INVALID | 获取新 token；原键、原版本、原正文重试一次；第二次失败整页刷新 |
| FORBIDDEN | 页面或弹窗内展示原文，停止操作；不白屏 |
| RESOURCE_NOT_FOUND | 原文提示，刷新成员列表 |
| METHOD_NOT_ALLOWED | 原文及 requestId 展示，作为请求缺陷排查 |
| REQUEST_IN_PROGRESS | 每 1 秒同键同正文自动重试，最多 2 次；仍失败可手动重试原操作 |
| REQUEST_TOO_LARGE | 原文及 requestId，停止自动重试 |
| MEDIA_TYPE_UNSUPPORTED | 有正文统一 application/json 预防；仍发生则原文展示 |
| RATE_LIMITED | 原文展示，按 Retry-After 计算截止时间，禁用提交；activity 的限流按服务器等待时间单独调度 |
| INTERNAL_ERROR | 原文及 requestId，允许用户手动重试 |
| DEPENDENCY_UNAVAILABLE | 原文提示；成员操作保留原请求快照供重试，不声称邮件已发送 |
| PRECONDITION_REQUIRED | 原文提示，关闭旧操作、刷新列表后重新确认 |
| VERSION_CONFLICT | 原文提示，关闭旧操作、刷新列表，绝不自动覆盖新版本 |
| IDEMPOTENCY_CONFLICT | 原文提示，结束旧操作；用户重新打开操作时生成新键 |
| EMAIL_EXISTS | 邀请弹窗展示原文，允许修改邮箱 |
| LAST_ADMIN | 弹窗原文提示，允许用户调整操作 |
| MEMBER_HAS_OPEN_TICKETS | 原文提示，不提供未实现的交接界面 |
| INVALID_STATE | 原文提示并刷新列表 |
| INVALID_QUERY | 原文提示，可重置筛选 |
| TOKEN_INVALID | 原文提示；激活联系管理员，重置提供重新申请入口 |
| 网络失败/非 JSON 响应 | 只在不存在后端 message 时提供本地连接/解析提示；成员网络重试复用快照 |

## 差异与工程决策

| 项目 | 裁决与实现 |
| --- | --- |
| 参考图品牌与语言 | 替换为 OpenITSM 和中文；保留蓝色、白卡片、浅灰蓝背景、双栏登录、侧栏与紧凑成员表格 |
| 图中照片 | 使用项目内 CSS 建筑几何插画表达同类视觉，无远程图片依赖 |
| 超范围元素 | 去掉第三方登录、记住我、通知、全局搜索、导出、多余角色、最近活跃时间与全部工单内容 |
| 激活画面 | 图中是“查收激活邮件”，本次按 API 实现邮件链接进入后设置密码；无匿名重发邀请接口 |
| 密码规则 | 图中 8 位与特殊符号要求不采用；以 API 的 Unicode 12～64、字母和数字为准 |
| 邀请 If-Match | 用户通用要求与接口单项规格冲突，按 API 优先：邀请无现有成员版本，只带幂等键 |
| 解锁状态 | 原 API 缺锁定字段；经用户确认新增 UserVO.lockedUntil，并同步 API；无数据库迁移 |
| local 端口 | STATUS 历史为 18080，本次真实启动 8080；集中 BackendConfig.ts，支持进程变量覆盖 |
| CSRF 刷新 | 内存丢失时按 API 先 GET csrf；me 恢复 token。采用同源 Vite 代理，保留浏览器自动 Origin，不改安全配置 |
| 技术选择 | Vue Router 处理路由；无需 Pinia/axios。Node 内置测试运行器，无额外测试依赖。构建依赖遵循 Vite 的 Vue/TypeScript 工程方式 |
| 实现顺序 | 先关键策略失败测试与 lockedUntil 失败测试，再 HTTP/会话/路由、认证页、成员页、视觉、验证与文档同步 |
| 风险边界 | 真实邮件投递仍无后端实现；前端不生成假邮件链接，不展示假数据 |
