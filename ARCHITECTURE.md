# OpenITSM 架构与开发规范

> 本文说明**代码怎么组织、开发必须遵守什么规则**。
> 产品需求见 [PRD.md](./PRD.md)，当前进度见 [STATUS.md](./STATUS.md)。

## 技术栈

固定使用：

| 部分 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 3.4.13、Spring MVC |
| 数据访问 | MyBatis-Plus |
| 数据库 | PostgreSQL 17 |
| 迁移 | Flyway |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 账号邮件 | Spring Boot starter-mail / JavaMailSender、PostgreSQL 持久化任务、Spring 定时调度 |
| AI | Python 3.12.15、FastAPI（独立计算服务） |

不引入 DDD、微服务、事件驱动、CQRS、Redis、外部消息队列。没有明确需求时不引入新的架构层或技术组件。

数据库访问统一使用 MyBatis-Plus Mapper。禁止引入 Spring Data JPA、Hibernate、Spring JDBC Repository 模式，禁止 MyBatis 与 JPA 混用。

Java 负责数据、权限、事务和持久化。Python 只做 AI 计算：不访问数据库，不自动回复，不由浏览器直接调用。

## 项目结构

```text
source/
├── backend/
│   └── src/main/java/com/openitsm/
│       ├── OpenItsmApplication.java
│       ├── controller/
│       ├── service/
│       │   └── impl/
│       ├── mapper/
│       ├── entity/
│       ├── dto/
│       ├── vo/
│       ├── config/
│       ├── common/
│       │   ├── exception/
│       │   ├── response/
│       │   ├── constants/
│       │   └── utils/
│       └── ai/
│           └── client/
├── frontend/
│   ├── BackendConfig.ts
│   ├── tests/
│   └── src/
│       ├── api/
│       ├── components/
│       ├── composables/
│       ├── router/
│       ├── stores/
│       ├── types/
│       ├── utils/
│       └── views/
└── ai-service/
    ├── app/
    └── tests/
```

目录按类型划分，不按业务模块划分。**禁止新增架构层**，包括 `domain`、`repository`、`facade`、`manager`、`handler`、`aggregate`、`module`、`adapter`。本项目不采用这些目录。

`ai/client/` 存放调用 Python 服务的客户端，使用 JDK HttpClient 调用 Python，不经过浏览器。

### 本人密码与资料维护

本人改密复用 AuthController、AuthService/Impl、UserMapper、PasswordEncoder、会话撤销和账号令牌失效逻辑；成员锁内校验旧凭据并更新密码、authVersion 及成员 version，在同一事务撤销全部会话。审计只记录改密动作，不保存新旧密码或密码正文摘要，成功后前端清空会话并重新登录。

本人资料使用 ProfileController → ProfileService/Impl → UserMapper，仍属于原有三层。V10 扩展 app_user.skills_description，旧账号技能默认为空；姓名沿用既有字段，不修改旧迁移。资料更新复用成员行锁，与管理员编辑协调，姓名/技能更新不撤销会话。前端复用已有表单、请求和错误组件提供本人资料及改密入口，路由和登录后返回路径白名单同步维护。

M10 由 Java 从合法候选读取技能描述作为不可信参考文本，不把技能描述当作系统指令；Python 仍只做计算，不访问数据库。推荐结果仍经 Java 候选过滤并由用户确认，不增加员工读取其他成员完整资料的接口。

### 邮件与凭据

账号激活、密码重置和工单通知复用 Spring Mail、NotificationMapper 和既有持久化任务。业务事务只入队；调度器每 5 秒领取待发送任务，使用行锁、领取代次与有限重试，防止多个执行者覆盖结果。发送前检查账号、令牌用途/有效期及工单可见性；SMTP 接受后回写失败仍可能重复投递。

SMTP 使用 Spring Boot 原生 `spring.mail.*` 配置；M14 数据库配置保存后优先使用，未配置时回退部署配置。SSL/STARTTLS 由连接安全模式或标准 JavaMail 属性确定，不设置额外投递开关。保留 SMTP 有限超时、证书验证和外部服务器传输加密；本机测试可使用无认证明文接收器。

密码、模型 API 密钥及投递所需账号令牌直接保存在受数据库权限保护的表中，API 只返回凭据是否已配置，日志不记录内容。V17 升级前使用一次性脚本核验并转换旧密文，同事务保存恢复副本；旧密文未迁移时拒绝升级。数据库、附件与凭据备份须限制访问；迁移后的运行不依赖额外密钥文件。

工单通知仍按既定动作、收件人去重、排除操作者和固定最少邮件内容执行；账号撤销与后台发送使用成员行→任务行的锁序。Member 行的 FOR NO KEY UPDATE 保留，避免通知外键检查与工单锁产生循环等待。

### 写入与业务记录

Controller → Service/Impl → Mapper 保持不变。写请求复用 Cookie、Origin、CSRF；Service 在事务内重验当前角色、状态、归属。保留工单、文章和必要成员行锁，以及末位管理员/工单交接协调。追加回复和附件可以顺序成功；关闭后追加由当前状态拒绝，认领竞争只有一方成功。

知识正文编辑在请求体携带文章 version，防止两个页面覆盖正文；其他写入不使用通用版本请求头。工单/文章 version 用于页面更新，设置 version 用于标记模型连接诊断的适用配置。创建页面禁用重复点击，网络结果不确定时先核对列表，其他写入先刷新记录。

AuditService 只保存账号变更及工单业务历史，TicketOperationMapper 供详情页读取；不引入通用审计切面或重复日志。V17 将旧技术请求记录移至受限归档 schema，保留恢复能力，应用不再查询或生成这些记录；旧 Flyway 文件原样保留。

### 工单创建与负责人指定

M02 创建使用统一类型 ID、可选负责人和初始优先级，复用原有事务及分派业务记录/通知。指定负责人复用 `ticket.assignee_id`（V3 已有）、Controller → Service → Mapper，不增加平行归属字段。

初次指定在 `TicketServiceImpl.create` 的一个事务内完成，复用 M03 的目标有效性校验与分派业务记录。不从 Controller 串调两个写接口。`TicketNotificationService.enqueue` 的 assignments 动作同事务入队，排除操作者；自提自接不产生任务，任一失败整体回滚。实际投递由已有后台任务执行，创建事务不调用 SMTP。

候选查询复用既有 Service/Mapper，按 M02/M03 契约提供 ACTIVE ENGINEER/ADMIN 候选；M05 按 assignee_id 展示新单。claim 在工单锁内校验当前角色、负责人及 NEW 状态。Service 与前端 Policy 使用“处理角色且本人负责”的业务规则，管理员接管先转派本人，工程师/管理员互转保留归属，降为员工或停用前复用交接保护。

业务类型管理使用 TicketTypeController、TicketTypeService/Impl、TicketTypeMapper 及对应 Entity/DTO/VO，由 Java 校验角色、创建者及选项可用性，不引入通用字典框架或新架构层。

类型改名统一历史展示；V12 增加业务类型表及工单类型 ID 关联，以稳定 ID 聚合，名称通过关联读取。移除仅标记不可供新提单使用，保留历史引用。已有 business_type 按现存文本精确去重并建立关联，初始项仅管理员可管理；无旧值不预置类型。旧业务字段和已有迁移保留，升级验证工单总数及精确类型映射。

前端使用独立 TicketTypePage，复用现有路由、请求和错误处理；工程师与管理员可进入管理页，提单页提供跳转入口，员工仅获取可选类型。M05 类型查询、M09 分类写入及 M13 统计需随稳定 ID 关联同步，知识库分类不因此自动改造。类型校验与改名、移除在 Service 事务中协调，沿用现有锁序，不以客户端候选列表代替最终校验。

提单附件由前端在确定创建成功后逐个调用既有 M04 上传接口，与创建为独立请求和事务，分别显示结果。未知结果先刷新附件列表核对；明确失败可重新上传。文件仅保留于当前页面，工单成功后不重复建单，不引入服务端暂存。

描述继续按纯文本存储和渲染，截图走既有鉴权附件，不引入富文本编辑器或正文图片存储；本版不新增隐私工单字段或另一套可见范围，复用已有工单及内部内容权限检查。

建单前推荐复用 M10 的 Java → Python 计算边界，由独立建议请求接收当前四项表单信息，不创建临时工单。Java 负责会话、候选权限、类型校验与输出过滤；Python 不访问数据库，不自动分派。前后推荐共用候选按业务类型的已解决/关闭数量、当前未结束负载及技能描述，不发送历史正文；最终仅返回候选标识、姓名和简短理由。前端人工确认后才设置负责人，AI 失败不阻塞建单；不增加提单标签字段。

### 列表检索扩展

M05 扩展复用 TicketController/Service/Mapper 与现有分页：服务端将日期、人员、优先级及 lifecycle 转为有参数的查询条件，始终与可见范围、视图取交集；按 Asia/Shanghai 日期边界转换为时间范围，不对 created_at 逐行转文本比较。筛选选项由同一工单服务按可见数据和统一类型取回最小字段，不直接放开成员管理接口。ASSIGNED 默认未结束但支持历史切换；人员候选可含历史成员，不能与分派的 ACTIVE 角色校验混用。不为筛选引入搜索引擎或新架构层。

### 工单拒绝

拒绝沿用 TicketController → TicketServiceImpl → TicketMapper，不增加架构层。现有流转、回复与附件写入统一拒绝 REJECTED，读取和鉴权下载继续沿用原规则；前端 Policy、状态枚举及 M05 查询同步调整。

UserMapper/TicketMapper 的交接和负载查询按结束状态排除 CLOSED/REJECTED，保留 RESOLVED 待确认单的交接要求；不把拒绝写成解决时间，拒绝仅参与对应状态计数，不参与平均解决耗时。

### 简单统计与看板

目标取消固定 SLA 目标、超时和达标率，M07 定义口径、M13 单一接口展示。Java 使用 DashboardController → DashboardService/Impl → TicketMapper 做参数化聚合，复用工单时间、状态、负责人和统一类型，不调用 Python、不加缓存/消息队列或统计中间层。一次响应使用一致的数据库读取视图，状态/类型总量与人员负载能按定义对齐。

平均解决耗时使用当前 RESOLVED/CLOSED 的有效 resolvedAt-createdAt，不将 updateTime、拒绝时间或评分混入。全部聚合默认无日期限制，可按 createdAt 的创建日期范围过滤，沿用 M05 的时区和边界转换；同次响应使用相同参数和读取视图。列表/详情响应不再返回 sla，页面撤下时限和超时；保留 resolvedAt 以及旧 SLA 存储字段供历史核对。新建不再启用 SLA 跟踪，不删旧列、不修改旧 Flyway；冗余字段后续处理须先核对兼容。

### 附件存储

已确认保持工单原始描述只读、沟通只追加，不增加描述改写、评论删除或附件删除的 Controller/Service 接口。纠错通过既有公开回复和操作记录追溯；这不改变失败上传清理本次文件的补偿逻辑。

附件沿用 Controller → Service → Mapper，不新增架构层或依赖。`TicketAttachmentServiceImpl` 负责权限、文件读写及失败补偿；`AttachmentConfig` 绑定专用目录。V6 的 ticket_attachment 只存元数据与 SHA-256，文件使用 UUID 命名，不使用客户端路径作为存储路径，不放入静态资源目录。

从 `source/backend` 启动时默认目录为 `.local/attachments`，已加入后端 Git 忽略；`OPENITSM_ATTACHMENT_ROOT` 可用进程环境指定绝对路径。其他启动目录应显式指定该变量，避免工作目录改变存储位置。不修改 `.env`，不引入对象存储；变更存储目录时须先停止上传、迁移全部文件并核对元数据/哈希，再切换目录，不单独切换配置丢失既有文件。

BrowserSecurityFilter 仅对精确工单/知识上传路径的 POST 放行 multipart，仍校验会话、Origin 和 CSRF；Servlet 上限 100 MiB、额外 64 KiB 表单开销，Filter 与 Service 将工单限制为 10 MiB、知识为 100 MiB，其余请求保留 256 KiB 限制。文件以 CREATE_NEW 写入，事务明确回滚时只清理本次文件；提交结果未知、清理失败或进程异常退出时保留残留待人工核对，不自动扫描删除。数据库与文件目录必须一起备份和恢复。

列表、下载和操作记录均校验工单可见性，员工在数据库分页统计前过滤内部附件。下载只返回 octet-stream 与 attachment 处置，不返回本地路径或哈希；文件不存在/异常为依赖不可用。

### 知识附件

知识附件使用 KnowledgeAttachmentController、KnowledgeAttachmentService/Impl、KnowledgeAttachmentMapper 及对应 Entity/DTO/VO，沿用现有分层。V13 新增 Flyway 表保存 article_id、文件元数据、上传者、removed_at/removed_by；移除只改记录，保留成功文件，不修改旧迁移、不增加通用附件管理框架。物理文件复用专用附件根目录及独立 knowledge 子目录，沿用随机名称、安全下载和回滚补偿；100 MiB 文件按流处理，不以整个文件字节数组作为常规缓冲。

知识上传单文件上限为 104857600 字节，multipart 总请求额外允许 64 KiB；Servlet 上限与 BrowserSecurityFilter 精确知识上传路径配套，同时保留工单上传 10 MiB/原请求上限及其他 JSON 256 KiB 限制。不能仅抬高全局 multipart 上限而放宽工单接口。控制器及 Service 再校验文件数量、大小和文章权限，复用现有身份、CSRF 和文件响应方式，不引入新依赖。

正文修改、附件上传/移除、发布和下架锁同一文章并递增同一 version，沿用成员行→文章行顺序；附件写入仅本人作者工程师且 DRAFT，已发布须先下架。读取时再次按文章当前状态、调用者角色及附件未移除条件授权，不复用工单权限或靠文件 URL 判断。原作者降为员工后不能读草稿；管理员仅阅读草稿及发布/下架，不因附件新功能获得作者上传权。正文保持纯文本，知识文件不提供给 Python 或 AI 检索。

M09 已确认只使用业务类型，不增加平行分类或标签表/接口。Java 负责 NEW 提单人/负责人/管理员、IN_PROGRESS/ON_HOLD 负责人/管理员的权限状态检查；建议不写入，人工类型确认复用 TicketService 的成员→类型→工单协调、版本与审计。RESOLVED/CLOSED/REJECTED 不可修改；与受理、解决、转派及类型停用并发时必须最终重验。优先级继续走管理员专属 M03 动作，不通过分类接口绕过。

V15 为 ticket_type 新增可空 description，历史行保持空值；类型读写复用原 Service/Mapper 和权限。类型候选向 Python 同步提供可选说明，仍不发送内部备注或附件。

### AI 检索与草稿计算（拟实现）

M11/M12 继续使用 Java 取得和授权材料、Python 只做相关性及文本计算的边界。M11 的候选先从现有 PostgreSQL 及 Mapper 按公开问题信息检索，再交 Python 判断相关性；只允许真实已解决/关闭工单与已发布知识，返回前复核状态和来源 ID，不引入外部向量库或自动知识沉淀。具体候选召回与排序需在实现时用真实样本验证，源码阅读不能证明检索质量。

M12 草稿仅留前端当前页面，不增加服务端草稿表、草稿箱或恢复任务；Java/Python 计算完成后不将草稿正文写入调用状态、审计或业务记录。生成与正式回复独立，最后由现有 M04 以业务接口发表；工程师和管理员可生成未关闭/未拒绝的可见单草稿，包括 RESOLVED。纯计算请求不承诺恢复上次输出，不自动重试外部生成，避免为临时建议引入持久化草稿体系。

无相似来源时使用当前公开问题生成一般建议/补充询问，Java 校验 sources 为空并返回无来源标记，前端必须明确显示；有来源时只允许经授权的真实候选引用。检索服务故障不能当作无来源的正常输入继续伪装成功。

### 团队设置与模型连接

M14 使用 SettingsController → SettingsService/Impl → SettingsMapper 维护单团队配置及最近调用/测试状态，新增 Flyway 表及版本字段，不新增配置框架。Java 保存运行参数、完成权限及字段校验，SMTP 和模型客户端按保存版本读取运行快照；读取配置不触发连接测试或邮件发送。RuntimeSettingsService/Impl 从单例 team_settings 读取不可变快照，未保存才使用启动引导值；SmtpMailServiceImpl 每次投递创建独立 SMTP sender，AccountMailDispatchServiceImpl 的领取前校验也使用数据库配置，AccountMailConfig 保留 SMTP TLS/链接校验。

SMTP 密码、模型密钥及待投递账号令牌直接保存在受权限保护的数据库中，不依赖额外应用密钥文件。数据库及备份访问权限承担凭据保护；接口只返回 configured 标记，日志和业务记录不保存凭据。数据库未设置时可读部署引导参数，保存后以数据库为准，显式清除不能回退旧环境值。邮件按业务任务投递，无额外总开关。升级前用离线迁移工具校验并转换历史密文，保留受限备份；V17 在存在未转换密文时拒绝升级，具体步骤见后端 README。

Python 仍无数据库访问：Java 为每次计算提供服务端模型连接快照，仅用于模型客户端认证，不把密钥拼入提示词。PythonAiClient 使用有界响应订阅与总等待取消，Python 使用 HTTPX 分段超时和 asyncio 总等待；两端禁止重定向。Java 仅调用本机 loopback 地址，Python 用 `python -m app` 固定监听 127.0.0.1:8001、关闭代理头解析，并拒绝非本机来源和超大正文；不需要自定义内部令牌。服务应部署在同一受信任主机，不将 8001 端口转发至公网；Python 禁用文档/CORS，不接收页面执行脚本。

本版不建立每日额度扣减或预占体系，仅保留开关/配置/最近调用状态；模型连接测试在 AI 关闭时仍可由管理员主动执行，固定文本、独立限频，不自动保存表单或启用 AI。关停后新业务调用被阻止，在途结果返回前重验开关及权限，任何建议都不自动落业务数据。

本版不新增备份或成功附件的定期清理调度；逻辑移除保留物理文件，失败上传只补偿清理本次明确失败的文件。后续清理须另定保留与运维策略，不把普通草稿离页或模型请求结束扩展成历史数据删除。

### 前端页面组织

前端使用 Vue Router 管理页面，Vue 响应式状态保存内存会话，原生 fetch 统一请求；无需额外状态管理或 HTTP 依赖。页面放 `views/`，可复用 UI 放 `components/`，接口通信放 `api/`，会话放 `stores/`。Vue / TypeScript 使用两个空格缩进。

后端目标地址集中在 `source/frontend/BackendConfig.ts`，可通过启动 Vite 的进程变量 `OPENITSM_BACKEND_URL` 覆盖。开发页面固定 `http://localhost:5173`，Vite 同源代理 `/api`，保留浏览器自动生成的 Origin 和会话 Cookie。生产需同源反向代理与 SPA history fallback，本次不修改部署配置。

所有接口经 `HttpClient.ts`：携带 Cookie、管理 CSRF、解析统一响应和错误。提交中禁止重复点击，业务写入结果未知时先刷新核对，不自动重放。会话与 token 不写浏览器持久化存储。activity 每 60 秒检查实际交互，恢复会话本身不算交互；隐藏页面和 401 停止心跳。

M01 设计见 `source/frontend/M01-design.md`，使用与验证见 `source/frontend/README.md`。成员列表通过已有 UserVO 增加 lockedUntil，供解锁按钮显隐；沿用实体既有字段，无数据库迁移。

## 分层职责

调用方向单向：`controller → service → mapper → 数据库`。

### Controller

负责：接收请求、参数校验、调用 Service、返回结果。

禁止：写业务逻辑、直接操作数据库、直接调用 Mapper。

### Service

负责：业务逻辑、事务控制、调用 Mapper、数据转换。

接口放 `service/`，实现放 `service/impl/`：

```
service/UserService.java          接口
service/impl/UserServiceImpl.java 实现
```

事务注解放在实现类上。

### Mapper

使用 MyBatis-Plus，继承 `BaseMapper<XxxEntity>` 或写 `@Select` / `@Insert` 注解。

负责：数据库访问、CRUD、SQL。

禁止：包含业务逻辑，禁止反向调用 Service。

### Entity

数据库表映射对象，字段与表结构对应。

### DTO

接口输入对象，只承载请求参数。

### VO

接口返回对象，只承载响应数据。

## 命名规范

文件名必须表达职责和类型：

| 类型 | 命名 | 示例 |
| --- | --- | --- |
| Controller | `XxxController.java` | `UserController.java` |
| Service 接口 | `XxxService.java` | `UserService.java` |
| Service 实现 | `XxxServiceImpl.java` | `UserServiceImpl.java` |
| Mapper | `XxxMapper.java` | `UserMapper.java` |
| Entity | `XxxEntity.java` | `UserEntity.java` |
| DTO | `XxxRequest.java` / `XxxResponse.java` | `LoginRequest.java` |
| VO | `XxxVO.java` | `UserVO.java` |
| Config | `XxxConfig.java` | `SecurityConfig.java` |
| Exception | `XxxException.java` | `BusinessException.java` |

禁止 `ActionAck.java`、`Result.java`、`Data.java` 这类看不出职责的名字。文件名必须与其中的类名一致。

缩进：Java / Python 四个空格，Vue / TypeScript 两个空格。Java 类名 PascalCase、方法 camelCase，Python 用 snake_case。

## common 规范

`common` 只放与业务无关的公共代码：

| 目录 | 用途 |
| --- | --- |
| `exception` | 业务异常、全局异常处理 |
| `response` | 统一响应包装 |
| `constants` | 常量 |
| `utils` | 工具类 |

禁止任何业务代码进入 `common`，禁止 `common` 依赖其他包。

## Config 规范

`config/` 只存放数据库、MyBatis、Spring、安全和全局配置。安全过滤器、密码编码器、类型处理器等配置组件放在此处；业务流程仍由 Service 负责。

## 数据库

local 配置通过 Spring Boot Config Data 导入本机 `.env` properties 文件，兼容仓库根目录和后端目录启动；不引入 dotenv 依赖。凭据文件必须保持 Git 忽略，不能复制凭据到受版本控制的 YAML。进程环境变量和启动参数仍可覆盖本机文件。

- 表结构只通过 Flyway 迁移修改，文件放 `src/main/resources/db/migration/`。
- 命名 `V1__init.sql`、`V2__m01_accounts.sql`，顺序递增。
- **绝不修改已应用的迁移文件**，新增结构一律新建迁移。

## 开发流程

每次开发按顺序执行：

1. `cd source/backend && mvn verify`，必须 BUILD SUCCESS。
2. 本地启动：`mvn spring-boot:run -Dspring-boot.run.profiles=local`，确认应用启动、Flyway 迁移成功并调用真实业务接口。
3. 对运行中的应用做接口测试，覆盖成功路径、权限、非法输入，以及涉及并发或防重复的场景。
4. 更新 [STATUS.md](./STATUS.md)，写清完成范围、执行的命令和实际结果。

只跑单元测试不算完成。跑不起来、接口失败或未联调，只能标为部分完成。

前端初始化后执行 `npm exec vue-tsc -- --noEmit` 与 `npm exec vite -- build`。Python 初始化后执行 `python -m unittest discover -s tests`。

## 新增功能的检查清单

1. 确认功能属于哪个已有层，不为它新建层。
2. 按上面的命名规范建文件。
3. 不新增无必要的抽象。
4. 改完同步更新 STATUS.md；涉及结构或技术调整时同时更新本文档。

## 文档维护

| 文档 | 职责 | 何时更新 |
| --- | --- | --- |
| PRD.md | 产品需要什么 | 需求变化时 |
| ARCHITECTURE.md | 代码怎么组织、怎么开发 | 目录、技术栈、规范变化时 |
| STATUS.md | 现在做到哪里 | 每次功能完成时 |

接口规范维护在 [API.md](./API.md)，作为前后端开发与联调的约定。接口变更时同步维护请求、响应、错误和权限，不在接口文档中重复维护产品需求或项目进度。

开始任何任务前先读这三份文档，并在同一次变更中更新受影响的文档。三份文档职责不重叠：PRD 不写代码结构，ARCHITECTURE 不写进度，STATUS 不写需求。

### M14 Python 运行与配置持久化

按用户指定复用本机 uv 管理的 Python 3.12.15，不另装新版解释器。source/ai-service 使用 pyproject.toml、uv.lock 与项目 .venv；运行版本范围 >=3.12,<3.14，此调整不改变 Java/Python 职责，也无历史 Python 服务迁移。app/main.py 提供固定文本模型连接测试与 M09 类型建议计算；无数据库、脚本执行或 M10～M12 占位接口。

测试编排使用短事务完成管理员/会话校验、限频及 RUNNING 记录，再在事务外等待 Python，最后以 attempt_id 条件更新结果并重验权限；状态更新不递增配置版本，旧尝试不能覆盖更新尝试。显式清除用 MyBatis ALWAYS 更新空值，避免 null 被忽略而恢复旧凭据。

模型使用一个兼容 Chat Completions 的连接，Base URL 包含 API 前缀。Java 与 Python 仅在同机 loopback 通信，Python 固定监听 127.0.0.1 且拒绝非本机访问。生产模型要求 HTTPS，loopback HTTP 测试另需 Python 进程 OPENITSM_AI_ALLOW_LOCAL_MODEL=true。部署配置及凭据不由设置页维护。RuntimeSettingsService 提供业务 AI 开关、配置完整性和返回前版本重验；具体业务权限和候选过滤随 M09～M12 接入。

### M09 直接调用与结果校验

提单前与详情页共用 ClassificationSuggestionService 的候选查询、Python 计算及返回前复核。创建前入口只接收标题、描述和可选影响范围，检查当前有效成员；详情入口沿用工单角色及状态规则。Python 共用原内部接口，影响范围可为空，不引入临时工单或新持久化表。创建页用局部请求序号和卸载标记丢弃过时结果，AI 状态独立于人工提交；最终创建仍走原 M02 事务。

Java 在 AI 开启、模型连接完整且用户具备操作权限时直接调用 Python，不要求先测试连接。Python 使用已有 HTTPX 调用普通 Chat Completions，提示词要求仅返回三字段 JSON，不附加供应商专用输出协议、不进行模式探测或自动重试。

Python 用现有 Pydantic 动态候选类型校验 JSON 字段、类型 ID、优先级、长度和字段关系；拒绝额外/缺失字段、重复键、非 JSON、截断及拒答，不自动修复输出。Java 反序列化后复核当前业务权限、状态、配置和候选有效性；建议仍需人工确认，优先级继续通过管理员原有操作修改。

管理员连接测试只发送一次固定短文本，检查正常响应，仅用于主动诊断。保留测试/调用状态、配置版本、时间和 attempt_id，旧在途结果不能覆盖新尝试。认证失败、请求受限、请求被拒绝、超时和无效响应分别返回脱敏错误；供应商响应正文和凭据不进入日志或页面。

V18 解除 V16 的历史 output_mode 状态约束，旧列值保留为历史数据；应用不再读取、写入或传递该模式，现行接口不返回 outputMode。测试缺失、失败或对应旧版本均不阻止业务请求；每次实际返回仍须通过结果校验。
