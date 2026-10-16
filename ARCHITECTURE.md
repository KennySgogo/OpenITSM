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
| AI | Python 3.13、FastAPI（独立服务，尚未创建） |

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

`ai/client/` 存放调用 Python 服务的客户端，Python 服务初始化前不创建占位代码。

### 账号邮件

`AccountMailServiceImpl` 负责事务内写入加密邮件任务；`SmtpMailService` / `SmtpMailServiceImpl` 负责 SMTP 与中文邮件内容；`AccountMailConfig` 负责配置绑定、校验和传输连接参数，沿用既有包结构。新增 starter-mail 的原因是复用 Spring 的 SMTP/MIME 实现，版本由 Spring Boot 管理。

发送默认关闭，账号、授权码、发件地址及网站基础地址从进程环境注入，不写入 Git。SMTP 有有限连接/读写超时，启用证书主机名校验；外部服务器要求认证及 SSL 或强制 STARTTLS。明文 SMTP 只允许 local/test 的本机测试接收器；HTTP 邮件链接只允许 local/test 的 loopback 地址，其他部署使用 HTTPS。

邀请和重置链接分别指向前端 `/activate?token=…`、`/reset-password?token=…`。`AccountMailSchedulingConfig` 仅在发送开关开启时定时调用 `AccountMailDispatchService`；不开启时不创建调度、不领取任务。未新增发信 HTTP 接口。

`NotificationMapper` 用 `FOR UPDATE SKIP LOCKED` 原子领取任务，复用已有 attempts 与 lease_until；递增 attempts 同时作为领取标识，结果更新必须匹配它及 RUNNING 状态，旧执行者不能覆盖新执行者或撤销结果。现有 V2 字段已足够，无新增迁移。

投递由 Service 编排两个独立事务：短事务领取并提交；随后按成员行→任务行顺序加锁，在独立投递事务中检查令牌、调用 SMTP、回写结果。邀请/重置/停用请求不调用 SMTP，但同一成员的变更可能等待正在进行的发送。锁顺序与既有账号操作一致，防止失效操作已提交后又开始发送旧任务。SMTP 读写超时与数据库事务超时不是发送总耗时的严格上限。

投递前解密并核对 token 哈希、所属成员、用途、代次、使用状态、有效期及成员状态；无效任务结束为 FAILED。SMTP 已接收但回写失败时保留 RUNNING，待租约回收后复用同一令牌重试，因此可能收到重复邮件，不能保证恰好发送一次。失败有上限退避，耗尽租约的最终尝试结束为 TIMEOUT；不删除历史记录。

### 附件存储

附件沿用 Controller → Service → Mapper，不新增架构层或依赖。`TicketAttachmentServiceImpl` 负责权限、版本、幂等、文件读写及失败补偿；`AttachmentConfig` 绑定专用目录。V6 的 ticket_attachment 只存元数据与 SHA-256，文件使用 UUID 命名，不使用客户端路径作为存储路径，不放入静态资源目录。

从 `source/backend` 启动时默认目录为 `.local/attachments`，已加入后端 Git 忽略；`OPENITSM_ATTACHMENT_ROOT` 可用进程环境指定绝对路径。其他启动目录应显式指定该变量，避免工作目录改变存储位置。不修改 `.env`，不引入对象存储；变更存储目录时须先停止上传、迁移全部文件并核对元数据/哈希，再切换目录，不单独切换配置丢失既有文件。

BrowserSecurityFilter 仅对附件上传 POST 放行 multipart，仍先校验会话、Origin 和 CSRF；Servlet 限制文件 10 MiB、总请求额外允许 64 KiB 表单开销，其余请求保留 256 KiB 限制。Service 计算内容摘要，沿用成员行→工单行锁，版本递增、审计、元数据和成功幂等同事务提交。文件以 CREATE_NEW 写入，事务明确回滚时只清理本次文件；提交结果未知、清理失败或进程异常退出时保留残留待人工核对，不自动扫描删除。数据库与文件目录必须一起备份和恢复。

列表、下载和操作记录均校验工单可见性，员工在数据库分页统计前过滤内部附件。下载只返回 octet-stream 与 attachment 处置，不返回本地路径或哈希；文件不存在/异常为依赖不可用。ready 同时检查数据库和附件目录是否为可读写目录；该检查不能保证磁盘余量或识别所有文件损坏，具体读写失败仍由接口返回 503。

### 前端页面组织

前端使用 Vue Router 管理页面，Vue 响应式状态保存内存会话，原生 fetch 统一请求；无需额外状态管理或 HTTP 依赖。页面放 `views/`，可复用 UI 放 `components/`，接口通信放 `api/`，会话放 `stores/`。Vue / TypeScript 使用两个空格缩进。

后端目标地址集中在 `source/frontend/BackendConfig.ts`，可通过启动 Vite 的进程变量 `OPENITSM_BACKEND_URL` 覆盖。开发页面固定 `http://localhost:5173`，Vite 同源代理 `/api`，保留浏览器自动生成的 Origin 和会话 Cookie。生产需同源反向代理与 SPA history fallback，本次不修改部署配置。

所有接口经 `HttpClient.ts`：携带 Cookie、管理 CSRF、解析统一响应和错误；成员操作保留请求快照以支持同键同正文重试。会话与 token 不写浏览器持久化存储。activity 每 60 秒检查实际交互，恢复会话本身不算交互；隐藏页面和 401 停止心跳。

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
2. 本地启动：`mvn spring-boot:run -Dspring-boot.run.profiles=local`，确认 Spring 容器启动、Flyway 迁移成功、`/health/live` 与 `/health/ready` 正常。
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
