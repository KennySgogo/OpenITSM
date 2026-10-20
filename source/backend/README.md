# M01 / M02 / M03 backend

Java 21 / Maven 3.9.16。需求与接口以仓库 PRD.md 和 ARCHITECTURE.md 为准，当前进度与已知问题见 STATUS.md。

当前构建与验收结果以仓库 STATUS.md 为准；历史包结构重构问题不作为当前失败结论。

## 构建与验证

在本目录执行 `mvn verify`。默认集成测试通过 Testcontainers 创建 PostgreSQL 17.11。也可提供测试进程环境变量 `OPENITSM_TEST_ADMIN_JDBC_URL`、`OPENITSM_TEST_DB_USER`、`OPENITSM_TEST_DB_PASSWORD`，使用现有 PostgreSQL；测试会创建随机名称的独立 `openitsm_m01_test_*` 数据库，不在已有数据库执行重置表操作，不自动删除数据库。测试账号必须允许 CREATE DATABASE。测试专用密钥写入系统临时文件，无生产用途，不自动清理文件。

## 本地运行

在进程环境中提供 `OPENITSM_DB_URL`、`OPENITSM_DB_USER`、`OPENITSM_DB_PASSWORD`。密码通过外部受控配置注入，不写入 Git。执行 `mvn spring-boot:run -Dspring-boot.run.profiles=local`；默认仅监听 127.0.0.1:8080。

local profile 也会通过 Spring Boot 原生配置导入读取仓库根目录的 `.env`（支持从仓库根目录或 `source/backend/` 启动）。文件采用 Java properties 的 `KEY=value` 格式，不加 shell 引号或 `export`；Windows 路径使用正斜杠，例如 `C:/path/account-mail.key`，非 ASCII 字符使用 `\uXXXX` 转义以兼容 properties 加载器。文件已被 Git 忽略，授权码和密码只留在该本机文件中。IDEA 仍须激活 `local`；已有进程环境变量、VM 系统属性和启动参数优先于文件中的同名配置，需清除冲突值后重启。非 local 环境不导入此文件。

`OPENITSM_ORIGIN` 是浏览器页面的精确 origin，默认 local 为 http://localhost:5173。HTTP 会话只限 local/test 和 loopback；生产必须 HTTPS，使用 `__Host-openitsm-session`。写请求需带 Origin 和 X-CSRF-Token。先 GET /api/v1/auth/csrf 接收匿名 Cookie 与 CSRF，登录后使用旋转后的 Cookie/CSRF。GET /auth/me 不续期；真实交互可每分钟 POST /auth/activity。

## 首个管理员

构建后，在交互式终端设置 `OPENITSM_BOOTSTRAP_NAME`、`OPENITSM_BOOTSTRAP_EMAIL` 以及数据库环境变量，执行：

```powershell
java -jar target/backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=bootstrap
```

bootstrap profile 禁用 Web；从控制台读取密码和确认，不提供默认密码，不从命令行参数读取密码。成功后持久化永久关闭标记；数据库已有任何账号时也拒绝初始化。普通服务启动不执行初始化。

## 邀请与重置邮件

`OPENITSM_ACCOUNT_MAIL_KEY_FILE` 指向部署方已受控创建的密钥文件，其内容是 Base64 编码的 32 字节 AES 密钥。应用只读取，不生成、不覆盖密钥。缺少或无效时邀请写入回滚，重置请求对所有邮箱返回同样的依赖错误。不会将原始账号令牌返回 API、写日志或明文存库。

M01 原子写入加密 notification_task，状态为 PENDING；重发/消费/停用取消旧任务。阶段二已接入自动投递，但默认关闭；只有显式开启并正确配置后，才会读取待发送任务。投递器使用 recipientId:template:tokenGeneration 作为 AES-GCM AAD 解密，核对令牌哈希、所属成员、用途、代次、有效期、使用状态和成员状态。密钥丢失会使既有待发送链接无法解密；须受控恢复密钥或重新发起链接，不能退回明文。

### SMTP 配置

通过 IDEA 的 Environment variables、进程环境或 local 自动导入的本机 `.env` 提供配置；后者采用前述 properties 格式。不要提交邮箱账号或授权码，不把凭据复制到 YAML。以下值是配置示例，不包含真实账号。

| 环境变量 | 用途与示例 |
| --- | --- |
| OPENITSM_ACCOUNT_MAIL_ENABLED | 默认 false；邮件投递总开关，启用前核对配置和积压收件人。账号任务开启后自动处理；工单任务另需开启 OPENITSM_TICKET_MAIL_ENABLED |
| OPENITSM_SMTP_HOST | SMTP 主机，例如 smtp.163.com |
| OPENITSM_SMTP_PORT | SMTP 端口，默认 465 |
| OPENITSM_SMTP_USERNAME | 完整发件邮箱，由部署者本地填写 |
| OPENITSM_SMTP_PASSWORD | SMTP 授权码，由用户本地填写，不能使用邮箱登录密码替代授权码 |
| OPENITSM_MAIL_FROM | 发件邮箱，通常与认证邮箱相同 |
| OPENITSM_PUBLIC_BASE_URL | 邮件中网站地址，本机联调为 http://localhost:5173 |
| OPENITSM_SMTP_SSL_ENABLED | 默认 true，适用于已核实的 163 SSL 465；不要同时开启 STARTTLS |
| OPENITSM_SMTP_STARTTLS_ENABLED | 默认 false；使用其他服务商强制 STARTTLS 时配置为 true，并关闭 SSL |

连接超时 5 秒，读取与写入超时各 10 秒；禁止通过信任全部证书绕过 TLS。正常运行只支持加密认证的外部 SMTP；无认证明文 SMTP 仅用于 local/test 的本机测试接收器。生产网站地址必须 HTTPS。

`AccountMailSmtpTest` 使用本地 TCP 接收器实际接收 MIME 邮件，不转发到真实邮箱；覆盖两种邮件及参数、配置和失败处理。默认配置不创建调度、不会连接 SMTP。真实 163 认证与送达属于第三阶段。

`/forgot-password` 页面提交到 `POST /api/v1/auth/password-reset-requests`。该接口受理后创建加密任务；后台投递必须在实际运行的后端进程中启用，不能依赖临时联调进程。配置 `OPENITSM_ACCOUNT_MAIL_ENABLED=true` 后需重启后端，调度会自动处理队列。启用前须核对数据库和全部积压收件人；切换数据库时也须重新核对。接口统一提示不代表已送达；不存在或非 ACTIVE 账号不发信，同邮箱每小时最多 5 次且间隔至少 60 秒，429 按 Retry-After 等待，不能通过修改任务或限流记录绕过。

### 持久化任务、重试与并发

| 项目 | 当前实现 |
| --- | --- |
| 调度 | 开启发送后，启动 5 秒开始第一轮；每轮结束后等待 5 秒继续；每轮最多领取 10 个任务 |
| 租约 | 每次领取 120 秒；attempts 递增，作为执行者标识；领取事务立即提交 |
| 最大次数 | 共 4 次尝试，包含初次发送；崩溃后重新领取也占一次 |
| 退避 | 第 1/2/3 次失败后，分别等待 60/300/900 秒；第 4 次失败结束为 FAILED |
| 崩溃恢复 | RUNNING 租约到期后可重新领取；第 4 次仍因崩溃未完成的任务结束为 TIMEOUT |
| 无效任务 | 账号不可用、令牌失效或载荷被篡改，不发送，任务结束为 FAILED |
| 撤销 | 重发邀请、激活、重置密码或停用时，旧 PENDING/RUNNING 任务由原有流程取消；旧执行者不能覆盖取消结果 |
| 成功 | SMTP 返回接受后更新 SUCCESS；这表示 SMTP 接受投递，不保证收件箱最终送达 |
| 错误记录 | error_code 只保存 SMTP_UNAVAILABLE、TOKEN_INVALID、PAYLOAD_INVALID 等固定分类，不保存授权码、令牌或完整异常 |

同一成员的发送与账号变更通过成员行→任务行锁顺序串行。SMTP 调用位于后台的独立投递事务，可能让该成员的重发、激活、停用等操作短暂等待；不会放入邀请 HTTP 请求的事务。连接/读写超时及 45 秒数据库事务超时不是 SMTP 总耗时的严格墙钟上限。

SMTP 已接受但数据库回写失败时，任务仍为 RUNNING，恢复后可能再次发送同一链接。邮件任务提供有上限的重试，不保证 exactly-once；重复链接仍只能成功消费一次。默认保留历史任务，不自动清理，保留期仍按 PRD 待确认事项处理。

AccountMailDispatchTest 使用独立 PostgreSQL 测试库、本地 SMTP 和可控时钟，覆盖发送、字段校验、失效取消、并发领取、租约恢复、退避、最后一次崩溃、回写失败后的重复投递。原 M01 测试强制关闭自动发信，避免本机真实邮箱配置影响测试。

## 后续模块边界

M02 已通过 V3 建立 ticket 表及编号序列。`POST /api/v1/tickets` 使用现有登录 Cookie、Origin、CSRF 和 Idempotency-Key，创建不需 If-Match；请求/响应见根目录 API.md。M02HttpTest 通过真实 HTTP 和隔离 PostgreSQL 验证提单、校验、幂等、并发及健康状态，前端已接入提单页面。

M03 后端提供认领/受理、分派、转派、挂起、恢复、解决、关闭、重开和调整优先级，均需当前工单 If-Match 与 Idempotency-Key。V4 增加优先级（新旧工单默认 P3）、解决摘要、关闭时间及轮次起点，未修改 V1～V3。重开清空关闭时间并重置轮次起点，保留有效负责人，原负责人已停用或降级时清空，由管理员后续分派；M07 现已接入本轮首次响应与解决时间，规则见 API.md。

`TicketMapper` 检查 status != CLOSED 的全部名下工单，包括 RESOLVED；M03 沿用 M01 的成员控制锁，再锁成员行与工单行，分派前核对 ACTIVE ENGINEER，避免与停用/降级竞争产生无效负责人。工单变更、原因审计和成功幂等记录使用同一事务。M03HttpTest 使用真实 HTTP、隔离 PostgreSQL 和可控时钟覆盖九个动作、权限、幂等、并发、七天边界、回滚与 V3 升级；不发真实邮件。

M03 前端已通过 M05 详情接入九个流转动作，未扩展 M04。测试可显式设置 `OPENITSM_M03_HOLD_SECONDS`（最多 600 秒），在 M03HttpTest 结束后短暂保留隔离数据库，供独立打包应用联调；默认不等待。

M05 提供 `GET /api/v1/tickets` 与 `GET /api/v1/tickets/{id}`，复用现有工单表，无新增迁移。四个视图、固定 20 条分页、精确筛选、关键词字面子串检索及权限规则见 API.md；返回真实姓名和版本，不返回敏感账号字段；SLA 字段由 M07 补齐。M05HttpTest 通过真实 HTTP 和 PostgreSQL 验证查询、权限和无写入副作用；`OPENITSM_M05_HOLD_SECONDS` 可在测试结束后保留隔离夹具最多 300 秒供浏览器联调，默认不等待。

M04 第一阶段提供公开回复、内部备注及操作记录的四个接口，规格见 API.md。V5 新增 ticket_reply 与时间线索引，复用现有工单 version、审计和幂等表；未修改旧迁移。员工的内部项在查询及分页统计前过滤，操作记录不向员工暴露内部原因或原始审计。详情页已接入，首次响应与 SLA 由 M07 计算。

`mvn -o -B -Dtest=M04HttpTest test` 运行真实 HTTP 与隔离 PostgreSQL 验证。先 `mvn -o -B verify` 生成最新 JAR 后，可在该测试进程中设置 `OPENITSM_M04_PACKAGED_VERIFY=true`，在测试结束时额外启动独立 JAR、验证健康与四接口并自动停止；此模式要求默认 Testcontainers 数据库，不使用已有数据库选项，禁止真实邮件发送。日志为 target/m04-packaged-app.log。

浏览器验收可显式设置 `OPENITSM_M04_HOLD_SECONDS`，测试后重新初始化隔离三角色账号并保留夹具最多 1200 秒，默认不等待；输出就绪地址及测试密钥文件路径，不输出密钥内容。可设置 `OPENITSM_M04_RELEASE_FILE` 为本次唯一的 target/ 文件路径，验收结束创建该标记即可提前结束等待并正常清理。该模式同样仅使用默认 Testcontainers，不使用业务数据库。

### M04 附件存储与验证

V6 新增 ticket_attachment 元数据表，不修改 V1～V5；实际文件由 Java 写入专用目录，使用随机 UUID 名称。默认从本目录启动时目录为 `.local/attachments`，已加入 Git 忽略。通过进程环境 `OPENITSM_ATTACHMENT_ROOT` 可指定绝对目录；从仓库根目录等其他位置启动时应显式指定，避免相对路径指向不同位置。不修改 `.env`，不安装额外存储依赖。

上传大小为 1～10485760 字节（10 MiB，含上限），Servlet 总请求额外容许 64 KiB 表单开销；仅上传接口支持 multipart。权限、字段、If-Match、幂等及下载响应见 API.md；下载不走静态文件服务。成功文件不自动清理，事务明确回滚清理本次文件；清理失败、进程异常或提交结果未知的残留只记录并保留人工核对。未提供暂存、删除或定时清理功能。备份/恢复必须同时包含数据库和文件目录。

`mvn -o -B -Dtest=M04AttachmentHttpTest test` 使用真实 TCP HTTP、隔离 PostgreSQL 和专用临时文件目录验证三角色、大小边界/chunked、分页、幂等/版本并发、文件回滚清理、存储故障和 V5→V6 数据保留。浏览器复用上述 M04 夹具，额外启动 JAR 时将附件根目录指向 target/ 内的验收专用目录；不使用业务附件。

开发 OpenAPI 只在 local profile 开启；/health/live、/health/ready 只允许 loopback。readiness 验证数据库可查询及附件目录存在、可读写，不能保证剩余磁盘空间或检测全部文件损坏；具体文件读写失败返回 503。无 CI、容器发布或生产部署改动。

## M07 处理时限

V7 新增本轮首次响应、解决时间及历史数据标识，不修改旧迁移。M07 无独立接口，列表和详情返回 `sla`，复用既有权限、工单锁、版本及幂等事务。按用户确认，仅对新工单保证完整指标，旧工单为 `sla: null`；重开后开始完整记录。规则和字段见根目录 API.md。

`mvn -o -B -Dtest=M07HttpTest test` 验证四档时限、精确边界、回复角色、挂起/转派/重开、优先级重算、并发/重放/回滚、读取无副作用及 V6→V7 升级。先完成 `mvn -o -B verify`，再设置进程变量 `OPENITSM_M07_PACKAGED_VERIFY=true` 运行该测试，可额外启动最新 JAR，验证健康与真实接口后自动停止。仅支持默认 Testcontainers 隔离数据库，自动关闭真实邮件，日志在 target/m07-packaged-app.log。

浏览器验收可同时设置 `OPENITSM_M07_HOLD_SECONDS`（最多 1200 秒）短暂保留独立应用和隔离夹具。验收后在 `OPENITSM_M07_RELEASE_FILE` 指定的唯一 target/ 路径创建标记以提前结束；默认不等待。浏览器通过临时 Vite 对接输出的应用端口，Origin 仍为 localhost:5173。

## M06 工单邮件通知

仅分派/转派、公开回复、解决、关闭、重开触发通知。收件人为提单人和动作后的负责人，去重并排除操作者；发送前重新检查 ACTIVE 状态和工单可见性。邮件仅包含编号、动作和需登录的详情链接，不含标题、描述、正文、原因、内部备注或附件。不新增前端页面和发信 HTTP 接口。

V8 扩展现有 notification_task；工单任务只存 ticket_id / ticket_action，不需要账号令牌加密密钥，账号 INVITE/RESET 仍使用原加密载荷。业务、通知任务、审计和幂等结果同事务提交；入队失败整体回滚。网络投递放在后台，失败沿用最多4次尝试、60/300/900秒退避和120秒租约；SMTP 接受后回写失败可能重复发信。

`OPENITSM_ACCOUNT_MAIL_ENABLED` 继续作为邮件投递总开关。工单邮件另需 `OPENITSM_TICKET_MAIL_ENABLED=true`，默认 false；不修改 .env，也不因已有账号邮件开关为 true 而自动发送新通知。关闭时工单任务仍入队，但不领取、不耗费尝试。启用前先核对数据库中全部积压工单任务及收件人；显式启用后会处理既有待发送任务。SMTP、发件地址和网站地址复用前述配置，localhost 网站链接只用于本机验收。

`mvn -o -B -Dtest=M06HttpTest test` 运行真实 HTTP、隔离 PostgreSQL 及本地 SMTP 验证。先完成 `mvn -o -B verify` 生成最新 JAR，再在测试进程设置 `OPENITSM_M06_PACKAGED_VERIFY=true` 运行该测试，可额外启动独立 JAR，以测试专用本机 SMTP 验证自动调度、实际 MIME 接收和幂等重放后自动停止。仅允许默认 Testcontainers 数据库，不使用已有数据库选项，不向真实收件人发送；应用日志为 target/m06-packaged-app.log。

## M08 文本知识库

V9 新增 knowledge_article，不修改已应用迁移；接口与角色、状态、查询及写入规则见 API.md。知识库使用现有 Service 事务、账号锁、文章锁和版本/幂等记录，不发送通知。

`mvn -o -B -Dtest=M08HttpTest test` 使用真实 HTTP 和隔离 PostgreSQL 验证生命周期、角色/降级、幂等、并发/回滚、查询分页、非法参数、GET 无副作用和 V8→V9 升级。先运行 `mvn -o -B verify` 打包，再设置进程变量 `OPENITSM_M08_PACKAGED_VERIFY=true` 运行专项测试，可启动独立 JAR，验证健康和实际接口；账号及工单邮件均关闭，仅允许 Testcontainers 数据库。日志在 target/m08-packaged-app.log。

浏览器验收可另设 `OPENITSM_M08_HOLD_SECONDS`（最多1200秒）和唯一的 `OPENITSM_M08_RELEASE_FILE` 标记路径；临时 Vite 使用 localhost:5179，以 OPENITSM_BACKEND_URL 指向日志输出的随机 JAR 端口。创建释放标记后自动停止应用和测试容器；默认不等待。
