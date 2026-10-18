# Resend 本地接入准备

## 2026-10-16 验证结果

- API Key 已保存在 `.env.resend.local`，不在此文档或日志中显示。
- 文件已被 `.gitignore` 和 `.dockerignore` 排除。
- 使用 `onboarding@resend.dev` 向注册邮箱发送测试邮件，查询结果为 `delivered`。
- 创建了 Resend Webhook，订阅 sent、delivered、delivery_delayed、bounced、failed、complained、suppressed。
- Webhook 签名密钥直接写入本地配置，应用已加载。
- 回调测试邮件的真实标识仅保存在本地，不随源码发布。
- Resend 控制台显示 sent 和 delivered 两条事件 Success；MySQL `mail_delivery_events` 已存入对应真实事件。
- 回调入口无签名 POST 返回 401；公网首页返回 404。本地代理的 reports 和 sse 路径均返回 404。
- 测试邮箱已配置绑定至 admin；此绑定用于将来创建的新预警，不修改旧预警的归属。

## 当前边界

应用发送渠道仍为 SMTP/Mailpit。此次测试通过独立 API 请求发送，不会开启已有业务任务的真实外发。
独立测试邮件没有项目 ReportTask，因此回调记录尚不关联业务任务；不能据此声称整条 MCP 业务链路已验证。
仍待完成：MCP 工具服务改造、业务邮件发送接入、提醒与确认的真实邮件联调。
尚未有自有发信域名：目前仅向 Resend 注册邮箱进行真实收信测试。

## 组件与地址

- 主应用：`http://localhost:8080/`，继续本地访问。
- 回调代理：`http://localhost:8086/api/mail/webhooks/resend`。
- 代理仅允许此路径的 POST；主应用继续校验 Svix 签名。
- Cloudflare Quick Tunnel 提供临时 HTTPS 地址；地址记录在 `data/resend-callback.json` 和本地配置 `RESEND_WEBHOOK_URL`。
- 临时回调地址不是发信域名，也不是学生聊天页面地址。不要把它配置为 `MINDHARBOR_PUBLIC_URL`。
- `MINDHARBOR_PUBLIC_URL` 目前为 `http://localhost:8080`，仅适用于本机点击邮件链接测试。

## 重启后恢复

先启动 Docker 和正常项目，再在项目目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-mail-callback.ps1
```

脚本启动两个回调容器、获取当前临时 URL、更新本项目已有的 Resend Webhook，并将签名密钥和账号绑定加载到应用。
它不会发送邮件，也不会切换到真实邮件发送渠道。主应用会短暂重启。
如果之后执行普通 `start-local.ps1`，需要再次执行上述脚本才能恢复回调配置。

Quick Tunnel 用于开发测试，没有可用性保证；进程重建可能更换地址，电脑/Docker 关闭后无法接收回调。

## 停止

在 Resend Webhooks 页面将本项目 Webhook 设为 Disabled，然后执行：

```powershell
docker compose -p mindharbor-callback -f docker-compose.callback.yml stop
```

再次运行启动脚本会更新 URL 并启用同一个 Webhook，不重复创建。

## 参考

- https://resend.com/docs/webhooks/introduction
- https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/
