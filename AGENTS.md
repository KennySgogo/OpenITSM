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

## 通用模块开发规则（模块开发任务优先采用）

本节是开发功能模块时的统一执行标准。若本节与本文件其他规则存在冲突，模块开发任务以本节为准；其他任务仍遵循其对应规则。用户在对话中提出“开发 `<模块名>` 模块”时，将 `<模块名>`作为本节中的目标模块，自动套用以下流程，不要求用户重复提供本节内容。

### 一、开发前理解项目

1. 阅读项目根目录的 `PRD.md`、`ARCHITECTURE.md`、`STATUS.md` 、`API.md`，明确目标模块的功能范围、接口定义、业务规则和验收标准。
2. 检查现有项目目录、代码、数据库结构及已实现模块，理解当前项目的实际实现方式。
3. 以现有代码和架构文档为依据，优先复用已有组件、工具类和通用能力，不重复实现。
4. 如果文档存在冲突、缺失或与实际代码不一致，先说明问题，不得自行创造业务需求或擅自修改已确定的技术方案。

### 二、架构要求

严格遵循项目现有分层架构。后端按现有 Spring MVC 结构实现：

- `Controller`：负责请求接收、参数校验和响应返回。
- `Service`：负责业务逻辑和事务处理。
- `Mapper`：负责数据库访问。
- `Entity / DTO / VO`：负责数据模型和接口数据传输。

前端按现有 Vue、TypeScript、Vite 和 Element Plus 结构实现，复用现有路由、请求封装、会话、错误处理及组件。具体包结构、命名规范、依赖管理及技术栈以 `ARCHITECTURE.md` 和现有代码为准。

禁止以下行为：

- 擅自调整项目架构、技术栈或现有目录结构。
- 在 Controller 中直接编写业务逻辑或数据库操作。
- 绕过既有分层结构，创建新的架构体系。
- 为简单功能引入不必要的设计模式、抽象层或第三方依赖。
- 未经必要性验证，重构与目标模块无关的代码。

### 三、功能边界

只开发需求文档中明确规定的目标模块功能。

1. 严格按照接口文档实现对应接口，包括请求方式、路径、参数、响应结构及错误处理。
2. 不得自行增加、删除或修改业务功能、接口及字段。
3. 不得为了扩展性、通用性或未来需求增加额外功能。
4. 不得提前开发其他模块或后续功能。
5. 不得使用 Mock 数据、硬编码结果或空实现冒充已完成的功能。
6. 对于目标模块依赖但尚未实现的其他模块，应优先复用现有能力；若无法完成集成，明确说明阻塞原因，不得擅自扩大开发范围。

遵循原则：需求要求什么，就实现什么；没有明确要求的，不做。

### 四、开发执行

1. 根据目标模块的需求和接口定义，确定需要新增或修改的文件。
2. 按照现有项目规范完成 Controller、Service、Mapper、必要的数据模型及前端页面/组件。
3. 保持接口、业务逻辑、数据访问和页面职责清晰。
4. 优先使用项目已有的异常处理、参数校验、响应封装、请求封装及其他公共能力。
5. 仅在目标模块必需时修改公共代码，并避免影响已有模块。
6. 保持代码简单、可读、易维护，不进行过度封装或无意义抽象。

### 五、验证要求

开发完成后必须验证：

- 目标模块的功能和接口符合需求及接口文档。
- 项目能够正常编译。
- Spring Boot 主启动类能够按项目实际类名正常启动。
- 目标模块相关接口能够在实际运行环境中调用。
- 关键业务逻辑、数据库操作、页面交互和异常处理符合预期。
- 现有已实现模块未因本次修改受到影响。

禁止仅凭单元测试通过就认定开发完成。优先执行已有测试，必要时补充与目标模块直接相关的最小测试，不建立复杂或冗余的测试体系。对于因环境、数据库或外部依赖导致无法验证的内容，必须明确说明，不得虚报测试通过。

### 六、文档同步

开发完成后同步检查并更新：

- `STATUS.md`：记录目标模块的实际完成情况、已验证内容和遗留问题。
- `ARCHITECTURE.md`：仅在实际架构发生必要变化时更新。
- `PRD.md`：原则上不修改，除非发现明确错误并获得确认。
- `API.md`：仅修正实现过程中确认的必要差异，不擅自扩展接口。

文档保持简洁清晰，不增加冗余技术解释、复杂评估报告或无实际意义的内容。

### 七、最终输出

完成目标模块后，简要汇报：

1. 已实现功能：目标模块实际完成的需求。
2. 修改文件：新增和修改的主要文件。
3. 运行验证：编译、启动、接口、页面及相关测试的实际结果。
4. 未完成事项：存在的阻塞、问题或需要确认的内容。
5. 文档更新：本次同步修改的文档。

不要输出冗长的开发过程、无意义的技术总结或未来功能规划。

### 核心执行原则

先阅读文档 → 理解现有代码 → 明确目标模块边界 → 遵循现有架构开发 → 实际启动验证 → 同步状态文档。

最小必要修改，严格控制范围，不扩展需求，不重构无关代码，不进行过度设计。接到“开发 `<模块名>` 模块”的请求后，直接读取本文件，将模块名套入本节规则并执行，不停留在分析或规划阶段。
