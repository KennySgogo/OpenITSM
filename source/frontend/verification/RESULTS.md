# 前端验证记录

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
