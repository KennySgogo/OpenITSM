# M01 前端

Vue 3 + TypeScript + Vite + Element Plus。Vue Router 管理导航；Vue 响应式状态保存内存会话；原生 fetch 统一处理请求。没有其他运行时 UI 依赖。工程配置参考 [Vite 官方指南](https://vite.dev/guide/)。

```powershell
cd source/frontend
npm ci
npm exec vue-tsc -- --noEmit
npm exec vite -- build
npm test
npm exec vite
```

开发地址为 http://localhost:5173，必须使用 localhost 保持 Origin 与后端 local 配置一致。后端本次实际启动在 8080。目标地址集中在 `BackendConfig.ts`；需要换端口时，在启动 Vite 的进程设置 `OPENITSM_BACKEND_URL`，不要修改 `.env`。

Vite 将 `/api` 同源代理到后端，保留浏览器 Origin 和 Cookie。不手动设置浏览器禁止脚本设置的 Origin 请求头，不使用跨站 Cookie，也不需要放宽后端 CORS。生产需由同源反向代理转发 `/api`，并对页面路径配置 SPA history fallback；本次未修改生产部署。

## 设计与代码

- [设计说明](./M01-design.md)：路由、页面、接口、错误处理四张表及差异。
- [验证记录](./verification/RESULTS.md)：实际执行结果与未联调项。
- `src/views/`：AuthPage（四条认证路由）、MembersPage、PlaceholderPage。
- `src/components/`：品牌、统一错误展示、应用导航壳。
- `src/api/HttpClient.ts`：Cookie、CSRF、错误解析、幂等处理中的有限重试和限流截止时间。
- `src/stores/SessionStore.ts`：会话恢复、实际交互跟踪、60 秒心跳和 401 清理。
- `src/router/`：history 路由、认证守卫；应用壳在页内处理角色不足。
- `src/utils/Policy.ts`：Unicode 密码规则、角色权限、锁定状态。
- `tests/`：Node 自带测试运行器，覆盖密码、权限、锁定、CSRF、幂等头、错误原文、401 与 Retry-After。

邀请 API 没有 If-Match，按 API.md 仅附 Idempotency-Key。其余成员写请求使用列表 version，格式为带引号的 If-Match。一次操作保留请求快照，网络失败/503/处理中的手动重试复用原始内容和键。409 版本冲突只刷新列表，由用户重新打开并确认操作。

会话、CSRF、密码与邮件 token 均不持久化到 localStorage/sessionStorage。邮件链接为 `/activate?token=…`、`/reset-password?token=…`。页面使用 no-referrer，避免 token 通过引用来源头传播；不加载远程图片、字体或分析脚本。

本地数据库不提供默认前端登录账号。联调测试账号仅在隔离 Testcontainers 数据库中由测试初始化，不可当作本地业务库凭据使用。正常账号由既有后端 bootstrap 或管理员邀请建立。
