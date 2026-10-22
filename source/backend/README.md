# OpenITSM backend

Java 21、Spring Boot 3.4.13、MyBatis-Plus、PostgreSQL 17。接口见根目录 API.md。

## 启动

从 source/backend 运行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

local 自动导入仓库根目录或当前目录的 `.env`（Java properties 格式）。数据库使用 OPENITSM_DB_URL、OPENITSM_DB_USER、OPENITSM_DB_PASSWORD；凭据保留在被忽略的文件或部署环境中。默认只监听 127.0.0.1:8080。

本地 HTTP 会话仅允许 loopback；正式部署必须使用 HTTPS、Secure 的 host Cookie 和正确 Origin。登录、角色授权、Origin/CSRF、账号限流及账号撤销照常生效；没有开发免登录配置。

## 邮件

部署使用 Spring Boot 标准邮件属性，例如环境变量 SPRING_MAIL_HOST、SPRING_MAIL_PORT、SPRING_MAIL_USERNAME、SPRING_MAIL_PASSWORD。local 的 `.env` 使用属性名 `spring.mail.host`、`spring.mail.port`、`spring.mail.username`、`spring.mail.password`。

默认采用 SMTP SSL 465；其他服务器按要求使用标准 `spring.mail.properties.mail.smtp.ssl.enable`、`spring.mail.properties.mail.smtp.starttls.enable`、`spring.mail.properties.mail.smtp.starttls.required`。不允许关闭外部连接的证书校验；无加密无认证仅用于 local/test 的本机 SMTP 接收器。

OPENITSM_MAIL_FROM 配置发件地址，OPENITSM_PUBLIC_BASE_URL 配置邮件链接的前端地址；正式环境使用 HTTPS。本地可用 http://localhost:5173。

管理员通过 M14 保存的 SMTP 连接优先于启动配置。邮件按账号激活、重置密码和工单动作入队，调度器每 5 秒处理；没有额外启用开关。最多 4 次尝试，失败后等待 60/300/900 秒，发送前检查账号、令牌和工单可见性。SMTP 接受后数据库回写失败可能重复投递。启动前核对现有积压任务和收件人，验证使用隔离库和本机接收器。

## V17 升级

1. 停止旧后端，备份数据库及附件；保留旧密钥文件用于恢复。
2. 含旧密文的数据库先运行一次性迁移工具。数据库 properties 文件包含既有 OPENITSM_DB_URL、OPENITSM_DB_USER、OPENITSM_DB_PASSWORD，不把凭据放在命令行。

```powershell
java -cp <postgresql-jdbc.jar> scripts/MigrateLegacySecrets.java <database.properties> <old-key-file>
java -cp <postgresql-jdbc.jar> scripts/MigrateLegacySecrets.java <database.properties> <old-key-file> --apply
```

默认只校验并回滚。`--apply` 在同一事务内锁表、验证全部密文、将原 notification_task/team_settings 复制到受限 `openitsm_legacy_v17` schema，然后转换原数据；任何错误整体回滚，重复执行被拒绝。程序只输出数量，不输出密码、令牌或密钥。没有旧数据的新库无需此步骤。

3. 启动新后端，由 Flyway 应用 V17；未迁移的密文会阻止升级。V17 重命名投递令牌列，并将旧技术请求记录移到受限归档 schema，业务表和工单历史保留。V1～V16 保持原样。
4. 核对业务记录数量和连接配置。账号令牌、SMTP 密码、模型密钥由数据库访问权限保护，接口只返回凭据配置状态；数据库、归档表及备份都按敏感数据管理。恢复旧版本时停止应用并恢复升级前数据库及附件备份；不要让旧代码读取转换后的数据。恢复副本和旧密钥由人工在确认备份后处置，不自动清理。

## AI 服务

Java 的 OPENITSM_AI_SERVICE_URL 默认 http://127.0.0.1:8001，仅允许同机 loopback HTTP。Python 从 source/ai-service 执行 `python -m app`，固定监听 127.0.0.1:8001 并忽略代理头，拒绝非本机客户端。浏览器只调用 Java；供应商模型凭据仍仅用于模型服务认证。

连接测试仅用于管理员主动诊断，不作为 AI 建议前置条件；业务调用直接校验实际 JSON 结果。V18 解除历史输出模式对调用状态的约束，旧列值保留，应用不再读取或写入该模式；配置和业务数据不清空。

## 附件与数据

OPENITSM_ATTACHMENT_ROOT 指定附件目录，默认 .local/attachments；知识附件使用其 knowledge 子目录。工单单文件 10 MiB，知识单文件 100 MiB。下载始终验证当前业务权限。数据库与文件一起备份，切换目录前迁移并核对已有文件；不自动删除成功文件。

工单写入在事务内检查身份、负责人和当前状态；并发认领只有一个成功，回复与关闭串行。知识正文编辑在 JSON 提供读取时的 version，过期返回 409；其他接口无需通用版本请求头。创建请求结果不确定时先核对列表，不自动重放。

## 验证

```powershell
mvn verify
```

默认由 Testcontainers 启动隔离 PostgreSQL 17；测试不会连接业务库。复用现有数据库时，只允许明确的测试专用库：测试进程使用 OPENITSM_TEST_ADMIN_JDBC_URL、OPENITSM_TEST_DB_USER、OPENITSM_TEST_DB_PASSWORD；OPENITSM_TEST_DATABASE 仅接受 openitsm_m01_test_ 加 32 位十六进制名称，启动测试对应 OPENITSM_TEST_STARTUP_DATABASE 为 openitsm_startup_test_ 加 32 位十六进制名称。未指定库名时创建独立测试库并保留，禁止填写业务库名。

真实 HTTP 测试启动 OpenItsmApplication，覆盖账号、工单、知识、配置和邮件失败路径。LegacySecretsMigrationTest 验证旧密文拒绝升级、错误密钥回滚、恢复副本及升级后的可读数据。

完整 verify 打包后，可在测试进程设置 OPENITSM_M09_PACKAGED_VERIFY=true 并运行 M09HttpTest，使用真实 JAR → Python → 本机模型接收器验证，不访问真实供应商。OPENITSM_M09_HOLD_SECONDS 可保留临时夹具用于浏览器验收，最长 1200 秒，结束会停止本次临时进程。
