# M01 验证记录

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
