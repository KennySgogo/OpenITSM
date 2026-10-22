# OpenITSM frontend

Vue 3、TypeScript、Vite、Element Plus。接口规格见根目录 API.md。

## 运行

```powershell
npm ci
npm exec vite
```

默认 http://localhost:5173，BackendConfig.ts 通过 OPENITSM_BACKEND_URL 选择代理目标，默认 http://127.0.0.1:8080。后台 Origin 须与页面源一致。

## 已接入

- 登录、会话恢复、退出、邀请激活、找回与重置密码、本人资料和改密。
- 成员管理、统一业务类型、提单及可选负责人/优先级/附件。
- 工单列表筛选、详情、流转、回复、内部备注、附件及业务操作历史。
- 知识草稿、正文编辑、发布/下架、附件与检索。
- 统计看板、管理员 SMTP/模型设置、连接测试及详情页 AI 分类建议与人工确认。

HttpClient 统一 Cookie、CSRF、错误原文、401 与 Retry-After；仅 CSRF 校验失败会刷新凭据并安全重试一次。业务写操作不自动重放，提交期间禁用重复点击。建单结果不确定时引导核对“我的工单”；回复或附件结果不确定时先刷新列表，保留当前输入便于核对。

知识正文编辑在 JSON 中提供打开编辑时的文章 version，冲突保留草稿，取消后重新打开最新正文核对。其他操作按服务端当前状态检查。工单/文章版本用于刷新相关内容，设置版本用于显示连接诊断结果是否对应当前配置；连接测试不作为 AI 建议生成门槛。

SMTP 密码和模型 API 密钥只允许替换或显式清除，查询只显示是否配置；保存邮件连接后供业务通知使用。模型测试只使用已保存配置，不自动开启 AI。M09 建单前建议及 M10～M12 仍未开发。

## 验证

```powershell
npm test
npm exec vue-tsc -- --noEmit
npm exec vite -- build
```

浏览器验收需启动真实后端并使用隔离数据库；构建成功不替代接口或页面验收。历史截图与结果在 verification/，当前验收结论见根目录 STATUS.md。
