# OpenITSM AI service

本阶段只实现 M14 固定文本模型连接测试，不包含 M09～M12 的业务接口。Python 不访问数据库；Java 完成管理员权限、版本、限频和连接快照组装。

## 本地运行

按本机已有环境使用 uv + Python 3.12.15，不下载或安装新版 Python：

```powershell
uv sync --locked --no-python-downloads
uv run --no-sync python -m unittest discover -s tests -v
uv run --no-sync python -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --no-access-log
```

启动前由部署端向 Java、Python 两个进程注入相同的 `OPENITSM_AI_INTERNAL_TOKEN`（至少 32 字符），不把凭据提交到仓库。Java 的 `OPENITSM_AI_SERVICE_URL` 默认 `http://127.0.0.1:8001`，也可通过 `openitsm.ai.service-url` / `openitsm.ai.internal-token` 进程参数绑定。不得从浏览器调用 Python，不开放 CORS 或 Swagger UI；非 loopback 部署须配置服务端网络隔离及 TLS。

注意：Python 不会自动读取仓库根目录 `.env`；仅把令牌写入该文件并不能让当前 Python 进程获得它。启动终端或 IDE 的运行配置必须显式提供该环境变量，修改后需重新启动 Python。不要把包含 SMTP、数据库等无关凭据的根 `.env` 整体导入 Python。

模型地址要求 HTTPS；仅隔离本机模型测试时，可为 Python 进程设置 `OPENITSM_AI_ALLOW_LOCAL_MODEL=true`，Java 同时使用 local/test profile。HTTPX 不读取进程代理环境，始终校验证书、禁止重定向；部署网络需允许服务器直连配置的模型地址。

## 接口与凭据

`POST /internal/v1/model-connection-tests` 使用内部 Bearer 认证，正文仅含 `modelConnection: {baseUrl,model,apiKey}`。Base URL 包含 API 前缀，Python 追加 `/chat/completions`；只发送程序内固定测试文本，成功仅返回 `{"status":"SUCCESS"}`。凭据不进入提示词、业务材料、日志或响应。输入校验错误不返回原始输入值。

连接/读/写/连接池超时分别为 2/5/3/2 秒，总等待 10 秒，响应最多 64 KiB；认证、供应商故障、无效输出、超时均明确报错，无自动生成重试。Java 提供配置保存和脱敏状态，失败不改变 AI 开关；无真实供应商验证时不能把本机测试当作生产模型可用证明。

无需数据库迁移或数据库客户端。数据库迁移 V14 由 Java 管理；数据库及设置加密主密钥需一同备份，Python 不保存模型密钥。
