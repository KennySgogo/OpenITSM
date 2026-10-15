# 仓库指南

## 项目结构与架构

OpenITSM 属于全新起步（Greenfield）项目：`source/backend/` 已建立 Java 基座及 M01 后端；`source/frontend/` 已初始化并实现 M01 页面，Python 尚未初始化。当前实际进度与已知问题见 [STATUS.md](./STATUS.md)。目录约定：

- `source/backend/`：Java 21 / Spring Boot 3.4.13；生产代码位于 `src/main/java/com/openitsm/`，测试位于 `src/test/java/`，数据库迁移位于 `src/main/resources/db/migration/`。
- `source/frontend/`：Vue、TypeScript、Vite、Element Plus；组件与静态资源位于 `src/` 下。
- `source/ai-service/`：Python 3.13.15 / FastAPI；应用代码位于 `app/`，测试位于 `tests/`。

Java 负责 PostgreSQL 17 的数据、权限、事务与持久化任务。Python 只执行经授权的 AI 计算，不访问数据库，也不自动回复。数据访问固定使用 MyBatis-Plus，代码放 `mapper` 包，类名 `XxxMapper`（如 `UserMapper`、`TicketMapper`）。

Java 包按类型划分，不按业务模块划分：`controller`、`service`（接口）与 `service/impl`（实现）、`mapper`、`entity`、`dto`、`vo`、`config`、`common`、`ai/client`。禁止新增 `domain`、`repository`、`facade`、`manager`、`handler`、`aggregate` 等层级。完整规范见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

不要引入 DDD、微服务、事件驱动、CQRS、Redis 或外部消息队列。

## 构建、测试与开发命令

Java 命令已可用；前端/Python 命令需在各自初始化后使用。请在各自的服务目录下运行：

- 后端：`mvn verify` 构建并测试；`mvn spring-boot:run -Dspring-boot.run.profiles=local` 本地启动。
- 前端：`npm ci` 安装锁定依赖；`npm exec vue-tsc -- --noEmit` 检查类型；`npm exec vite -- build` 构建；`npm exec vite` 启动开发服务器。
- Python：`python -m unittest discover -s tests` 运行测试；`python -m uvicorn app.main:app --host 127.0.0.1 --port 8001` 本地启动。

## 代码风格与命名

Java/Python 使用四个空格缩进，Vue/TypeScript 使用两个空格。Java 类与 Vue 组件使用 PascalCase；方法使用 camelCase；Python 使用 snake_case。

文件名必须表达职责和类型，且与类名一致：`XxxController.java`、`XxxService.java`（接口）、`XxxServiceImpl.java`（实现）、`XxxMapper.java`、`XxxEntity.java`、`XxxRequest.java` / `XxxResponse.java`、`XxxVO.java`、`XxxConfig.java`、`XxxException.java`。禁止 `ActionAck`、`Result`、`Data` 这类看不出职责的命名。

保持 controller 精简：只接收请求、校验参数、调用 Service、返回结果，禁止写业务逻辑或直接调用 Mapper。业务逻辑与事务放在 service 层，事务注解加在 `XxxServiceImpl` 上。

`common` 只允许 `exception`、`response`、`constants`、`utils` 四个子包，禁止业务代码进入，禁止 `common` 依赖其他包。

迁移文件命名为 `V1__init.sql`；绝不修改已应用的迁移。目前尚未配置格式化工具或 linter。

## 测试指南

使用 Spring Boot 测试，并通过 Testcontainers 连接真实 PostgreSQL；Python 使用 unittest 配合 HTTPX；前端进行类型检查与构建检查。测试文件命名为 `*Test.java` 和 `test_*.py`。需覆盖变更涉及的验收标准，尤其是权限、并发、失败与幂等性。尚未设定覆盖率指标。记录实际结果；未执行的测试永远不算通过。

## 提交与 Pull Request

目前没有提交历史。使用简洁的英文祈使句提交信息，例如 `Add backend health checks`。PR 需描述行为、关联相关的 M/MA 需求与 issue、报告验证结果，并说明迁移；UI 变更需附截图。M01 测试默认用 Testcontainers；使用已有 PostgreSQL 时只能创建或显式复用测试专用数据库，环境变量说明见 `source/backend/README.md`，不记录连接凭据。

## 贡献者与 Agent 规则

每次任务前先阅读 `PRD.md`、`ARCHITECTURE.md` 和 `STATUS.md`。检查一致性，并同步更新受影响的文档。这三份是唯一的项目全局上下文文档，各自职责不重叠：PRD 只写产品需求，ARCHITECTURE 只写代码结构与规范，STATUS 只写进度与问题。

新增功能必须：先确认属于哪个已有层，遵循命名规范，不新增无必要层级，完成后同步更新 STATUS.md。目录、技术栈或规范发生变化时同步更新 ARCHITECTURE.md。

默认使用中文沟通。变更需保持在已授权阶段范围内；架构变更需记录原因、影响与迁移计划。绝不自动提交或推送；经授权提交前先展示变更摘要。删除、改写历史、修改密钥/`.env`/证书/CI，或公开发布之前必须先询问。绝不记录凭据。
