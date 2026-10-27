# 前端验证记录

当前验收结果见首节；后续各节按阶段保留历史，不代表当前接口或待办状态。代码提交与双远程分支同步情况集中记录在根 STATUS.md。本机 `.local/` 截图及日志未提交，相关链接仅供验收机器查看。

## M12 AI 回复草稿（2026-10-27）

- 33 项前端测试、类型检查及构建通过。组件测试覆盖手动采用、已有回复保留、工单变化/离页忽略迟到结果和重新生成失败时保留有效草稿。
- 内置浏览器管理员通过默认前后端及 AI 服务，在主库合成工单 ITSM-43 上使用真实模型生成草稿，显示 ITSM-9 参考来源；手动采用后才填入公开回复框。生成和填入不增加业务记录或邮件任务。
- 暂停 AI 时页面显示不可用，回复框文字保留；恢复后可再次生成。正式发送成功与旧工单版本拒绝使用隔离 PostgreSQL HTTP 测试，未在主库发送回复。工程师与员工权限由后端集成测试覆盖，未完成逐角色页面验收。
- 临时桌面视口验收后恢复默认；已有窄屏遮挡与 Vite 包体积告警保留。截图：[草稿与真实来源](../../../.local/m12-runtime/browser-draft.png)，本机文件未提交。

## M11 相似问题检索（2026-10-23）

- 前端 30 项测试、vue-tsc 与 Vite 构建通过。实际组件 setup/lifecycle 测试覆盖换单、版本变化、刷新、卸载后丢弃迟到响应，以及空结果与错误的区别；实际渲染由下列浏览器样例补充。
- 主库管理员、默认 5173→最终 JAR 8080→FastAPI 8001→已配置真实模型：ITSM-43 无候选时显示暂无来源；既有合成验收 ITSM-9 人工受理/解决后，检索显示其真实标题、公开解决原文和理由，点击打开对应详情。
- 停止 FastAPI 后检索显示服务不可用和请求编号，未误报无匹配，人工搜索工单仍打开正常列表，随后恢复 AI。检索前后业务数量和工单版本一致，详见根 STATUS。
- 工程师/员工权限、知识来源和下架剔除由真实 PostgreSQL HTTP 测试覆盖；尚未以工程师页面验收知识命中/跳转。桌面视口验收后恢复默认，既有窄屏遮挡及 Vite 包体积告警保留。
- 截图：[真实来源](../../../.local/m11-runtime/browser-real-match.png)、[无命中](../../../.local/m11-runtime/browser-empty.png)、[依赖失败](../../../.local/m11-runtime/browser-dependency-error.png)。本机证据未提交。

## M10 处理人推荐（2026-10-23）

- 前端 28 项测试、类型检查与构建通过。新增实际推荐组件 setup/lifecycle 测试覆盖输入变化、手选负责人、禁用状态和卸载后的迟到结果；AI 使用独立加载/错误状态，失效后提示重新生成。
- 内置浏览器使用主库管理员、默认 5173→8080→8001 与真实模型。详情页推荐返回 3 人，负责人仍未分派；采用本地管理员后打开原分派确认框，确认后 ITSM-9 负责人更新，业务记录可见。
- 创建前维修样例两次真实请求超时，表单保留；人工选择本地管理员并提交 ITSM-43 成功，保持 P3。开发类型样例真实生成 3 人后负责人仍空，手动采用“堂大炮”后才填入负责人，P3 保持不变，未自动建单。
- 主库页面仅管理员，员工与工程师接口/权限由隔离 PostgreSQL HTTP 集成覆盖。真实模型耗时不稳定仍待跟踪；桌面视口验收后恢复默认，既有窄屏布局及包体积告警保留。
- 截图：[详情推荐](../../../.local/m10-detail-recommendations.png)、[创建前采用](../../../.local/m10-create-adopted.png)、[超时降级](../../../.local/m10-create-timeout.png)、[人工建单成功](../../../.local/m10-manual-create-success.png)。

## M09 阶段 C 与 AI 简化合并复验（2026-10-22）

- 27 项前端测试、vue-tsc 和 Vite 构建通过；新增测试挂载实际 CreateTicketPage 的 setup/lifecycle，以可控响应覆盖乱序、输入/类型变化、AI 失败、人工提交、页面卸载及类型失效，组件视觉行为另由以下内置浏览器验收。
- 默认 5173→最终 JAR 8080→最新 FastAPI 8001，使用用户授权主数据库、管理员和已配置真实模型。标题/描述填写后，无类型及影响范围即可生成；生成后类型仍空。手动采用“维修”后类型填入，模型建议 P4，表单保持 P3。
- AI 等待时修改影响范围，页面提示重新生成，迟到结果没有回填。再次发起生成后直接人工建单成功；主库 ITSM-9 为维修/P3。详情页再次生成真实建议，显示“建议尚未生效”，人工选用并确认后刷新仍正确；未运行前置结构化能力测试。
- 主库三角色页面未全部复验：仅提供管理员凭据，工程师/员工由后端 HTTP/PostgreSQL 集成覆盖。窄面板侧栏遮挡影响点击，桌面 1440×1000 视口完成验收并恢复默认；该既有窄屏布局问题保留待处理。主包大小告警保留。
- 截图：[采用前建议](../../../.local/stage-c-20261022/browser-before-adopt.png)、[已创建并确认](../../../.local/stage-c-20261022/browser-ticket-confirmed.png)。以下记录为前轮历史结果。

## AI 直接生成改动（2026-10-22）

- 设置页移除结构化协议模式和必须先验证的提示，连接测试明确为可选诊断，增加认证/限流/请求拒绝的错误分类。20 项前端测试、类型检查和构建通过，保留主包体积告警。
- 后端与真实 FastAPI/已配置模型在默认端口实际返回有效建议，员工人工确认、权限拒绝、AI 关闭时人工操作及旧诊断不阻塞生成均通过真实 HTTP 验证；生成不自动改工单、优先级不变。
- 本轮页面点击复验未完成：Windows 浏览器工具因无法可靠识别 URL 停止自动操作。下方截图为前轮业务验收，不作为本轮 AI 页面验收证据。日志及当前状态见根 STATUS 页首。

## 全项目技术清理页面验收（2026-10-22）

- 默认 5173 前端对接最终 JAR 8080 与 FastAPI 8001，连接隔离验收库及本机 SMTP，保留运行。
- 管理员登录、创建 ITSM-1、受理、公开回复、填写解决摘要、解决和关闭通过；刷新后已关闭、业务记录保留，上传和回复入口正确关闭。
- 工程师草稿附件通过真实接口上传后，页面在未选择文件时成功移除，刷新后仍显示暂无附件。修复清理过程中移除动作误用上传文件校验的问题；不再保留通用 pending 请求对象。
- 最终前端再次创建 ITSM-3 并发表公开回复，知识正文保存及业务类型创建/修改/移除通过。
- 20 项前端测试、类型检查和构建重新通过；既有主包 >500 kB 告警保留。
- 设置页真实结构化能力测试失败返回 503，显示连接暂不可用；独立普通模型请求为 200。真实供应商结构化能力及建议质量仍未验收。默认端口的账号激活/重置、实际 MIME 接收、指派/接单/处理/关闭及附件字节检查详见根 STATUS 本轮结果。
- 截图：[已关闭工单](../../../.local/cleanup-20261022/browser-closed.jpg)、[草稿附件移除](../../../.local/cleanup-20261022/browser-knowledge-remove.jpg)。下方旧阶段的版本头、重放和探针记录仅为历史，不再代表现行行为。

## M04 附件前后端验收（2026-10-16，Asia/Ulaanbaatar）

- 最终类型检查通过，Vite 构建成功（1652 模块，保留既有主包 >500 KB 告警），16 项前端测试全部通过；新增 FormData 经 CSRF 刷新后保留原请求、二进制下载和错误/401 处理验证。日志 `m04-attachments-typecheck.log`、`m04-attachments-build.log`、`m04-attachments-tests.log`。
- 最终 `mvn -o -B verify`：104 项、0 失败/错误/跳过，BUILD SUCCESS；新增 9 项真实 HTTP 附件测试，覆盖三角色/四种允许状态及关闭限制、10 MiB 精确边界和分块请求、输入与 CSRF、幂等摘要/重放、并发、分页过滤、失败回滚清理、存储故障及 V5→V6 保留旧工单/回复/审计升级。日志 `source/backend/target/m04-attachments-verify.log`。
- 最终 JAR 实际启动 OpenItsmApplication（local、18088、Flyway V6），live/ready 均 200 UP。只读真实 HTTP 复核员工附件 23 条、管理员 24 条；管理员内部附件下载 200、application/octet-stream、50 字节，SHA-256 与源文件一致，员工下载同一内部附件 404。日志 `source/backend/target/m04-attachments-app-final.log`、`m04-attachments-runtime-final.log`。
- 浏览器使用临时 Vite 5178 和隔离 Testcontainers PostgreSQL 17.11：员工真实提单、空文件/超限提示、公开上传/下载；工程师上传和下载内部附件；员工重新登录后看不到内部附件或其操作。浏览器完成附件 20+3 分页、独立操作记录分页、解决后上传、关闭后隐藏上传但仍可真实下载。本阶段管理员通过真实 HTTP 覆盖，未重复全部 M01 浏览器验收。
- 实际停止后端后上传，出现错误并锁定文件/可见性，回复按钮禁用；恢复同一隔离库后手动“重试原上传”成功。两个页面保留同一版本，一页上传后另一页提交显示版本冲突，文件和错误保留、工单刷新，无自动重提。
- 数据库核对验收工单 ITSM-14：CLOSED、version=27，23 PUBLIC/1 INTERNAL 附件、27 条审计、28 条成功幂等记录（含提单），`retry-log.txt` 仅 1 条；磁盘共 24 个 UUID 文件。证据 `source/backend/target/m04-attachments-db-check.log`。浏览器实际下载的公开/内部文件 SHA-256 分别为 `D12801A5E4AF4DE6C383F40EEE7E60FA039D2EFA9B7E61372AD7A25DAEFD1668`、`E912095B76DDE4BD99C3600E4F1AAA0D89809D4514D0835A3DC29F66A25D9197`，均与源文件一致。
- 截图：[工程师附件区](./m04-attachments-staff.png)、[断连重试锁定](./m04-attachment-retry.png)、[版本冲突](./m04-attachment-conflict.png)、[员工关闭限制与过滤分页](./m04-attachments-employee.png)。成功文件保留，异常退出残留不自动扫描删除；无暂存或删除接口。未连接业务库、未改 `.env`、未发真实邮件，未提交或推送。
- 收尾已停止本次临时 JAR/Vite，18088/5178 无监听；释放保留夹具后正常结束，9 项测试、0 失败/错误/跳过、BUILD SUCCESS。日志 `source/backend/target/m04-attachments-fixture.log`。

## M04 沟通与操作记录验收（2026-10-16，Asia/Ulaanbaatar）

- 类型检查通过；Vite 构建成功，1649 模块，保留既有主包 >500 KB 告警。`npm test`：14 项通过，新增沟通角色/状态边界及公开流转字段展示测试；日志 `m04-typecheck.log`、`m04-build.log`、`m04-tests.log`。
- 最终 `mvn -o -B verify`：95 项、0 失败/错误/跳过，BUILD SUCCESS；日志 `source/backend/target/m04-frontend-verify.log`。本阶段未修改后端业务或迁移，仅为 M04HttpTest 增加可选保留夹具（最长 1200 秒、默认关闭）及提前释放标记。
- 独立 JAR 实际启动 OpenItsmApplication，local、18087、Flyway V5；Vite 5177 对接本次 Testcontainers PostgreSQL 17.11。live/ready 均实际取得 200 UP；启动日志 `source/backend/target/m04-frontend-app.log`、`m04-frontend-app-restart.log`。未连接业务库、未改 `.env` 或默认端口、未发真实邮件。
- 员工真实提单 ITSM-14，空回复出现必填提示；公开回复成功后正文清空、记录刷新，`<script>` 作为文本显示。工程师在未负责工单上可公开回复及内部备注，管理员也可添加内部备注；内部内容和可见性提示明确。员工重新登录后只收到 24 条公开回复及 27 条公开操作，均隐藏两条内部备注及其操作，关闭记录不显示管理员内部原因；管理员可见 26 条沟通记录及 29 条操作。
- 19 条分页测试数据通过真实 HTTP 回复接口创建；浏览器实际验证 20 条/页、两区独立翻页、成功发表后显示最新页。M03 认领、解决及关闭均通过浏览器实际调用，状态变化和公开解决摘要同步出现在操作记录；RESOLVED 可回复，CLOSED 隐藏编辑区并显示新建入口。
- 两页面保留同版本：一页发表成功，另一页提交显示后端版本冲突，保留原正文和错误、刷新工单，无自动重提。实际停止本次后端后发表，显示响应错误，正文和方式锁定、M03 按钮禁用；恢复同一隔离库后“重试原请求”成功。数据库核对 ITSM-14 为 CLOSED、version=29，24 PUBLIC/2 INTERNAL、29 条工单审计、30 条成功幂等记录（含提单），重试文本仅 1 条；证据 `source/backend/target/m04-frontend-db-check.log`。
- 截图：[员工公开回复](./m04-employee.png)、[内部备注与操作记录](./m04-staff.png)、[版本冲突](./m04-conflict.png)、[员工关闭限制与过滤](./m04-employee-closed.png)。本阶段未实现附件/SLA，也未重复完整 M01 浏览器验收；已有模块由完整后端回归及前端测试验证。
- 收尾时客户端闪退；恢复后核对本次 5177/18087 服务及隔离容器已结束。保留夹具命令未留下最终完成报告，因此不把该命令计为测试通过；95 项结论来自已正常结束的最终 verify。未自动提交或推送。

## M03 前端验收（2026-10-16，Asia/Ulaanbaatar）

- 类型检查通过；Vite 构建成功，1645 模块，保留既有主包 >500 KB 告警。`npm test`：12 项通过，新增角色/负责人/状态权限及关闭后七天含边界、超界和无关闭时间测试。
- `mvn -o -B verify`：86 项、0 失败/错误/跳过，BUILD SUCCESS；包括 M01/M02/M03/M05 回归。日志 `source/backend/target/m03-frontend-verify.log`。后端业务、接口及数据库迁移未修改；测试夹具可选保留上限从 120 秒调为 600 秒供浏览器联调，默认不等待。
- 最终打包应用实际启动 `OpenItsmApplication`，local、18086、Flyway V4，live/ready 均 HTTP 200 UP；Vite 5176 对接本次 Testcontainers PostgreSQL 17.11。启动日志 `source/backend/target/m03-frontend-app.log`、`m03-frontend-app-restart.log`。未接触业务库、`.env` 或既有前端服务，关闭自动发信。
- 浏览器由员工真实提单进入详情，NEW 状态无处理操作；管理员分派、填写原因调整 P2；两个页面保留同一版本，其中一个更新成功，另一页面提交返回“工单已更新，请刷新后重试”，提示在刷新详情后仍保留，无自动重提。
- 工程师受理已分派单、填原因挂起、恢复、转派；转派候选排除当前负责人，转派后原负责人无处理入口。新负责人填写公开摘要解决工单，代关闭需原因；提单人确认关闭可无正文，且可填写原因重开。九个流转接口均由浏览器实际调用成功。
- 关闭弹窗保留已填原因后停止本次后端，提交显示真实代理错误，表单锁定并提供“重试原操作”；恢复同一隔离库的后端后重试成功。数据库最终工单 ITSM-20：IN_PROGRESS、P2、version=13，13 条流转审计、14 条成功幂等记录（含提单）。两次实际关闭各产生一次审计；版本冲突和断连未造成重复流转。证据 `source/backend/target/m03-frontend-db-check.log`。
- 截图：[流转操作](./m03-actions.jpg)、[版本冲突](./m03-conflict.jpg)。未在浏览器等待七天或重复全部 M01 页面；期限边界由权限测试及已有后端可控时钟测试验证。M04 回复附件、M07 SLA 均未扩展。
- 验收后主动停止本次临时 JAR/Vite 和处于可选保留阶段的测试夹具进程；该夹具命令因主动中止未生成最终完成报告，测试结论以已完整结束的 `mvn verify` 为准。

## M05 前后端验收（2026-10-16，Asia/Ulaanbaatar）

- `npm exec vue-tsc -- --noEmit`：退出 0；`npm exec vite -- build`：成功，1641 模块，保留主包 >500 KB 告警；`npm test`：现有 9 项通过。
- 后端 `mvn -o -B verify`：86 项、0 失败/错误/跳过，BUILD SUCCESS。新增 5 项 M05 真实 HTTP 测试验证四视图权限、分页排序、组合检索、通配符字面匹配、非法参数、详情可见性与读取无副作用；全部 M01/M02/M03 回归通过。日志 `source/backend/target/m05-verify.log`。
- 最终 JAR 以 local profile 在独立 18085 启动，Flyway V4、临时 Vite 5175，连接 Testcontainers PostgreSQL 17.11。使用测试初始化账号和真实落库工单，未连接业务库、未发真实邮件、未修改 `.env` 或原有 5173 服务。启动日志 `source/backend/target/m05-packaged-app.log`。
- 浏览器员工列表共 23 条：第一页 20 条、第二页 3 条；搜索 VPN 返回 1 条并重置页码，点击编号进入详情。提单人姓名、无负责人、公开解决摘要正确；描述中的 `<script>` 按文本显示。
- 浏览器工程师默认我的待办 1 条，未分派队列 24 条，全部工单 25 条；组合设置 OUTAGE、处理中、Network 后返回唯一匹配工单，显示真实提单人和负责人。未在浏览器逐页回归 M01，已有模块接口由完整后端测试回归。
- 截图：[组合检索列表](./m05-list.jpg)、[只读详情](./m05-detail.jpg)。本次按授权不返回 SLA、不开发 M03 操作区或 M04 回复附件。
- 验收后测试夹具按 300 秒保留期自动结束（5 项测试再次通过），停止本次临时 JAR/Vite。收尾时 live 返回 UP；ready 探测发生在隔离数据库清理后，返回 DOWN，未将其记为通过。此前浏览器实际读取均在数据库存活期间完成。

## M02 前端验收（2026-10-16，Asia/Ulaanbaatar）

- `npm exec vue-tsc -- --noEmit`：退出 0；`npm exec vite -- build`：成功，1635 模块，保留主包 >500 KB 告警；`npm test`：现有 9 项全部通过。首次构建受沙箱 realpath EPERM 限制，改为获准的正常权限执行后通过。
- 后端 `mvn -o -B verify`：70 项、0 失败/错误/跳过，BUILD SUCCESS，包含三角色登录后返回 `/tickets/new` 回归；日志 `source/backend/target/m02-frontend-verify.log`。
- 实际执行 `java -jar target/backend-0.1.0-SNAPSHOT.jar`，local profile、独立 18083 端口，连接本次 Testcontainers PostgreSQL 17.11，Flyway V3 成功；临时 Vite 使用 5174，Origin 匹配，自动发信关闭。未连接业务库、未改原 5173 服务。
- 浏览器验证：匿名进入提单页需登录，登录返回提单页；空表单显示四项必填提示；管理员/员工/工程师均可经导航提交，显示真实编号与 UUID；继续提单后表单清空。
- 真实断连验证：停止本次验收后端后提交，显示失败，四项内容保留并锁定，按钮为“重试提交”；重启同一隔离库后端后重试成功。最终数据库只读核对 ADMIN/EMPLOYEE/ENGINEER 分别生成 ITSM-1/2/3，共 3 张工单和 3 条提单幂等记录，无重复建单。
- 截图：[提单成功](./m02-created.jpg)。未做本次 M01 全页面浏览器回归；M01 接口由完整后端测试及既有前端测试回归。列表、详情、附件与 M03 均不在本次实现范围。

## M01 历史记录

日期：2026-10-15（Asia/Shanghai）。记录实际执行，不把模拟响应或未执行项目作为真实联调。

## 命令与输出

| 目录 | 命令 | 实际输出摘要 |
| --- | --- | --- |
| source/frontend | `npm install --no-audit --no-fund` | added 68 packages；生成 package-lock.json |
| source/frontend | `npm exec vue-tsc -- --noEmit` | 退出 0，无 TypeScript 错误 |
| source/frontend | `npm exec vite -- build` | Vite 7.3.7；1632 modules transformed；built successfully；主 JS 约 1.06 MB（gzip 351 KB），保留默认 >500 KB 告警 |
| source/frontend | `npm test` | tests 9 / pass 9 / fail 0 |
| source/backend | `mvn -B verify` | Tests run: 39, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS |
| source/backend | `mvn spring-boot:run -Dspring-boot.run.profiles=local` | Tomcat started on port 8080；PostgreSQL 17.11；2 migrations validated，schema V2 |
| source/backend | `mvn -B -Dtest=M01FrontendHttpTest test` | Tests run: 1, Failures: 0, Errors: 0；BUILD SUCCESS；真实 HTTP 联调标记 PASS |
| source/frontend | `npm exec vite` | VITE v7.3.7 ready；http://localhost:5173 |

## 联调证据

| 场景 | 实际执行与断言 |
| --- | --- |
| 登录成功 | 真实 HTTP 返回 200，redirectPath=/admin/users；浏览器用隔离测试账号登录后到该页面 |
| 登录失败 | 真实 HTTP 返回 401 AUTH_INVALID；浏览器显示后端“邮箱或密码错误，或账号暂不可用”与 requestId，无跳转循环 |
| 会话恢复 | 真实 HTTP me 返回 200；前端收到 token；Node 回归确认自动恢复不会被计作真实交互 |
| 找回密码 | 真实 HTTP 对存在/不存在邮箱返回同一 message；浏览器展示“如果该邮箱对应可用账号，你将收到重置邮件” |
| 成员列表 | 真实 HTTP pageSize=20，用户项包含 lockedUntil；浏览器读取隔离库真实成员 |
| 邀请与幂等 | 真实 HTTP 邀请携带 UUID key 返回 201，同键同正文返回 replayed=true；浏览器邀请隔离成员后列表新增 INVITED 记录 |
| 版本冲突 | 真实 HTTP 使用旧 If-Match 返回 409 VERSION_CONFLICT；Node 回归确认客户端不自动重试该写入；UI 实现关闭旧弹窗、刷新后重新确认 |
| 401 | 真实 HTTP 退出后 me 返回 SESSION_EXPIRED；浏览器匿名进入成员页跳 /login?returnTo=/admin/users，并展示后端会话失效原文 |
| 占位页 | 桌面浏览器看板明确“未实现/功能建设中”，无假统计或业务操作 |
| 激活/重置保护 | 浏览器无 token 访问时提示缺少有效令牌、按钮禁用；密码策略通过 Unicode 边界回归测试 |
| 心跳与隐藏 | Node 虚拟计时器：60 秒无交互不发请求；可信输入后上报；模拟请求耗时 5 秒仍按上次开始后 60 秒调度；隐藏不报；恢复后继续；清会话停止。非实际等待 2 小时的浏览器验收 |
| CSRF/429 | Node 传输层模拟故障测试：CSRF 旋转后仅重试一次，版本/键/正文不变；429 使用 Retry-After=120 计算截止时间。不是声称浏览器真实触发全部限流条件 |

## 环境与测试隔离

初次沙箱执行 npm DNS 解析失败；构建 realpath EPERM；Maven Docker/本地 PostgreSQL 权限失败。提升执行权限后以上命令均重新执行。无代理、全局配置、密钥或 CI 改动。

正常 local 服务连接原配置数据库，仅做匿名/失败登录与会话检查。成功登录、邀请、版本冲突等写入使用 Testcontainers 隔离测试库。浏览器 QA 期间临时将开发代理指向 local profile 的随机测试端口，检查后恢复 BackendConfig.ts 的 8080。测试专用密码没有写入产品页面或文档。

HTTP 测试支持可选进程变量 OPENITSM_FRONTEND_BROWSER_HOLD_SECONDS，最多保留测试服务 300 秒，供手工浏览器检查；常规 verify 不等待。测试服务日志打印 FRONTEND_BROWSER_FIXTURE_PORT，测试结束自动停止测试容器。

## 未联调项与原因

| 未联调项 | 原因 |
| --- | --- |
| 真实邮件送达、点击真实激活链接并设置密码 | 后端只有加密 PENDING 任务，无 SMTP 投递器；不伪造邮件链接 |
| 点击真实重置邮件链接并完成密码重置 | 同上；页面、密码校验及请求逻辑已实现，不能代替真实投递验收 |
| 浏览器持续 2 小时空闲/24 小时绝对到期 | 未实际等待该时长；后端已有可控时钟测试与前端计时器回归，不能称真实时长验收通过 |
| 所有错误码逐项浏览器触发、工程师/员工账号完整浏览器回归 | 本次浏览器主要覆盖管理员链路；错误处理与权限策略已有实现和部分自动测试，未逐个制造全部真实故障 |
| 工单交接保护完整业务链 | M02/M03 尚未实现，只有后端保护查询与测试，不在本次前端范围 |

## 截图

成员截图来自隔离测试数据库的真实 API 响应；不作为产品演示假数据。

- [成员管理](./members.png)
- [登录与会话失效提示](./login.png)
- [找回申请统一文案](./password-reset-request.png)
- [激活页令牌保护](./activation.png)
- [重置页令牌保护](./reset-password.png)

## 差异

详细裁决见 [设计说明](../M01-design.md)。关键差异：移除参考图超范围元素；密码按 API；邀请无 If-Match；授权新增 lockedUntil；local 本次为 8080；建筑图为 CSS 插画。

## M07 处理时限（2026-10-19）

- 类型检查、Vite 构建（1655 模块）、16 项前端测试通过；主包仍有既有体积告警。后端完整 verify 111 项通过；独立 JAR 与 M07 专项 7 项再次通过。
- 临时 Vite localhost:5173 → JAR 127.0.0.1:51976 → 隔离 Testcontainers PostgreSQL 17.11；关闭真实邮件，不使用业务库。受限环境中的首个 Vite 服务连接超时，核对并重启本次临时进程后完成验收，未改代理或系统配置。
- 员工列表同时显示 ITSM-14 响应/解决超时、ITSM-15 双达标、ITSM-16 双计时中；详情显示真实目标时间及 Asia/Shanghai 标识。员工重开 ITSM-15，两个实际时间清空、目标按本轮起点重算，保留旧解决摘要；手动刷新后状态一致。
- 工程师在重开后公开回复，首次响应变为已达标，实际时间为 2026-10-19 14:33:12（北京时间）；解决后实际解决时间为 14:33:30，解决达标。管理员调整为 P1，目标分别改为 14:47:43 / 18:32:43，实际时间保留，两项仍达标。
- 数据库最终核对 ITSM-15 为 RESOLVED/P1/version=7，本轮起点 06:32:43.634575Z，首次响应 06:33:12.428423Z，解决 06:33:30.541347Z；7 条工单审计（认领1、公开回复2、解决2、重开1、优先级调整1）。浏览器控制台无警告/错误。接口边界、权限、并发和回滚的自动验证详见后端 M07HttpTest；没有将这些场景虚报为浏览器手测。
- 本次临时 JAR/Vite 和隔离夹具已正常结束，5173/51976 无监听。架构和依赖无变化，未提交或推送。

截图：[列表三种状态](./m07-list.png)、[超时详情](./m07-overdue.png)、[重开重新计时](./m07-reopened.png)、[再次解决](./m07-completed.png)、[P1 重算](./m07-priority.png)。

## M08 文本知识库（2026-10-19）

- 后端全量 `mvn -o -B verify`：129项通过，无失败、错误或跳过；M08专项8项使用真实 HTTP/PostgreSQL，覆盖生命周期、角色及降级、幂等、并发/回滚、查询分页、非法请求、读取无副作用和 V8→V9 升级。前端类型检查、17项测试、Vite构建（1661模块）通过；保留既有主包体积告警。
- 最新独立 JAR 在 local、57586 启动，live/ready均200；真实接口完成草稿创建、发布、下架、编辑、重发。临时 localhost:5179 对接隔离 Testcontainers PostgreSQL 17.11，账号及工单邮件关闭，不使用业务数据库。
- 浏览器验证：工程师空表单必填提示、创建本人草稿；管理员 ALL 同时看到草稿与已发布，发布后显示发布时间，下架回草稿；双页旧版本下架提示“知识已更新”并刷新；员工直达下架知识不可访问；作者编辑下架草稿，管理员重新发布；员工用正文“客户端”与分类“网络支持”组合搜索得到1条，详情无编辑或发布入口，script标签按纯文本展示。登录后可返回获授权的知识详情。
- 构建产物预览中停止本次临时服务，保存失败后保留正文、版本和幂等键，锁定表单；恢复预览后点击“重试原操作”成功保存。最初使用 Vite 开发服务时重启触发热刷新，因此改用构建产物重新完整验证；没有把热刷新后重新编辑当作重试成功。
- 数据库只读核对浏览器文章 cfb3eb5e-4475-42af-8415-e78e95af54da 最终 PUBLISHED/version=4；创建0、发布1、下架2、编辑3、再发布4各只有1条成功幂等记录。最终阅读页面控制台无警告或错误。23条结果的20+3分页、角色降级、事务回滚等由真实HTTP自动测试覆盖，未冒称逐项浏览器手测。

截图：[草稿](./m08-draft.png)、[版本冲突](./m08-conflict.png)、[断连重试](./m08-retry.png)、[正文与分类搜索](./m08-search.png)、[员工阅读](./m08-published.png)。

客户端闪退后重新执行 M08 专项及独立 JAR 验证（64263）：8项通过、BUILD SUCCESS，自动停止应用；日志 `source/backend/target/m08-packaged-final.log`。验收端口5179/57586/64263均已停止，截图保留。


## M14 团队配置（2026-10-20）

- 环境：Testcontainers PostgreSQL 17.11、独立 OpenItsmApplication JAR、真实 Python 3.12.15/FastAPI，以及仅本机接收固定文本的模型测试服务。Vite localhost:5189 代理隔离后端；用户原有 5173 服务未停止或修改。不调用真实供应商，不发送真实邮件。
- 管理员登录后读取已保存配置，模型凭据仅显示已配置、输入框为空；保存团队名递增版本且不启用 AI、不自动测试。保存后旧版本的成功测试显示“旧配置结果”。AI 关闭下主动测试成功，返回对应保存版本；业务调用显示“暂无调用”。
- 立即重复测试返回 429；修正后仅测试按钮冷却，表单及保存仍可用，成功保存下一版本。两个页面以同一版本编辑时，先保存者成功，后保存者收到 VERSION_CONFLICT，输入保留；重新读取恢复最新版本。
- 员工登录后无管理导航，直接访问 /settings 显示无访问权限；员工工单列表仍可用。预期 409/429 为验收主动触发，未发现页面脚本异常。
- 截图：m14-settings-success.jpg、m14-cooldown-isolation.jpg、m14-version-conflict.jpg、m14-employee-denied.jpg。前端 `npm test` 20 项通过，vue-tsc 与 Vite 构建通过，保留既有主包体积告警。
- 验收结束释放夹具并停止临时 JAR/Python/Vite；不将本机测试服务成功视为真实供应商可用或真实邮箱送达。M01～M08 其他新增页面的完整验收不由本记录替代。
# M09 浏览器验收（2026-10-21）

以下按执行顺序保留历史记录：首次未完成的人工确认步骤已在阶段 A 补验通过，阶段 B 已完成结构化输出页面回归；阶段 C 提单前建议尚未实施。

- 独立 JAR、真实 Python、本机模型接收器及隔离 Testcontainers PostgreSQL 联通；Vite 使用 `http://localhost:5189` 代理独立后端，前后端健康入口返回 200。
- 应用内浏览器使用隔离员工账号登录，打开测试工单，生成已有类型建议，页面显示“建议尚未生效”、类型、理由和 P2 建议；工单原优先级仍为 P3。
- 人工类型切换的页面提交遇到限时后端夹具退出，前端显示“服务响应无法解析”；该浏览器步骤未验收通过。人工确认的独立 JAR 接口和真实 PostgreSQL 集成测试已通过。初次以 127.0.0.1 访问时与后端允许的 localhost Origin 不一致，改用 localhost 后登录成功。本机模型接收器不代表真实供应商。

## M09 补齐阶段 A：类型说明（2026-10-21）

- 最终后端全量 160 项通过；Python 12 项、前端 20 项、vue-tsc 与 Vite 构建通过，保留原包体积提示。独立最终 JAR、真实 Python 与本机模型接收器验证类型说明到达模型输入；日志 source/backend/target/m09-stage-a-final-regression.log、m09-stage-a-final-packaged.log。
- 使用隔离 Testcontainers PostgreSQL，临时 Vite localhost:5189 代理独立 JAR；夹具保留至页面结束后显式释放，未在本机业务库创建验收账号、类型或工单。
- 管理员编辑类型的中文多行说明，列表回显；员工提单选择该类型后显示说明，并成功创建 ITSM-2。详情人工确认其他类型成功，优先级仍 P3；补齐上次未完成的浏览器人工确认步骤。另修正分类审计查询白名单和中文标签，并在最终 JAR 页面确认操作事件显示。
- 员工直接进入类型管理页被拒绝；工程师可创建含说明的自有类型，仅自有行出现编辑/移除按钮。输入 501 字显示“说明最多 500 字”且保存禁用，清空说明后成功保存为“暂无说明”。
- 截图：m09-a-type-description.png、m09-a-create-description.png、m09-a-manual-confirm.png、m09-a-history-final.png。最终页面无脚本错误。临时 Vite/独立 JAR/Python/测试数据库夹具已停止；默认 5173/8080/8001 服务继续运行阶段 A 版本。
- 阶段 A 验收时，B/C 仍待实施；后续 B 已完成，见下节，C 仍待实施。本机模型接收器不证明真实供应商的分类质量。


## M09 阶段 B：结构化输出验收（2026-10-21）

- 使用最终独立 JAR、真实 Python、Testcontainers PostgreSQL 和本机模型接收器；临时前端 5189 代理隔离后端，未修改真实模型连接或调用真实供应商。先验证原生 JSON Schema，再让接收器明确拒绝 response_format，验证仅回退一次强制工具调用；说明与动态 ID Schema 完整传递。完整运行日志 source/backend/target/m09-b-packaged.log，专项 1 项通过；最终代码复核日志 m09-b-packaged-final.log。
- 管理员设置页显示“强制工具调用”，主动测试成功；仅修改隔离团队名称并保存后，旧测试显示“旧配置结果”，当前模式“未验证”。此时详情生成提示管理员测试，人工选 M09 Old 并确认仍成功，P3 不变；重新测试新版本后恢复能力。
- 员工登录同一隔离工单 ITSM-1，生成得到 M09 New/P2 建议时，实际类型仍为 M09 Old、优先级 P3；点击“选用建议类型”只填选择，点击“确认业务类型”后实际变为 M09 New，P3 不变，新增公开“确认业务类型”操作记录。员工不出现管理员优先级操作。
- 截图：m09-b-structured-mode.png（已验证模式）、m09-b-config-unverified.png（配置变化失效）、m09-b-employee-suggestion.png（建议未生效）、m09-b-confirmed.png（人工确认结果）。临时页面/服务/隔离库已在验收后释放，默认服务保持运行。
- 本次只完成阶段 B，不把提单页生成建议记为已完成；阶段 C 尚未实施。本机接收器只证明协议与应用链路，不证明真实模型质量。
