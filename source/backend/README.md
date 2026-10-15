# M01 backend

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
| OPENITSM_ACCOUNT_MAIL_ENABLED | 默认 false；第三阶段确认配置和历史待发送收件人后再启用。开启后会自动处理现有 PENDING 和租约过期 RUNNING 任务 |
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

M02 建立 ticket 表后，`TicketMapper` 自动检查 status != CLOSED 的全部名下工单，包括 RESOLVED。工单表尚不存在时计数为零；若已有表结构不兼容则报错，不能降级为零。M03 的分派/交接事务必须先锁定目标 app_user 并核对 ACTIVE ENGINEER，再修改 ticket；与停用/降级共享成员行锁。M01 尚不提供工单转派/HANDOVER 接口，相关端到端验收待 M03。

开发 OpenAPI 只在 local profile 开启；/health/live、/health/ready 只允许 loopback。当前 readiness 验证数据库可查询；附件卷检查随 M04 接入。无 CI、容器发布或生产部署改动。
