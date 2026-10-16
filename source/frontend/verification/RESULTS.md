# 前端验证记录

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
