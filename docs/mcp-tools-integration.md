# 独立 MCP 工具服务

## 实际链路

心理报告与工具任务写入 MySQL → 后台任务领取 → MCP Client → 独立 `mcp-tools` 服务。

- Excel：`mindbridge_write_excel_report` → 按报告 ID 去重 → 临时文件写入 → 原子替换正式文件 → 明确返回报告 ID 和成功状态。
- 邮件：`mindbridge_send_risk_alert` → 校验固定任务标识和冻结内容 → 工具侧持久化账本 → Resend API → 返回 providerEmailId。
- 投递：Resend 签名回调 → 主项目 `mail_delivery_events` → 更新任务投递状态；回调缺失时调用 `mindbridge_get_mail_status` 主动核对。
- 确认：辅导员登录并主动确认，停止后续提醒。每轮提醒是新的任务与发送标识，同一轮重试复用原标识。

Excel 和邮件是独立持久化任务，Excel 失败不会阻塞邮件。学生回复不等待这些工具任务。

## 启动与位置

在项目目录运行 `powershell -ExecutionPolicy Bypass -File scripts/start-mcp.ps1`。构建完成后可加 `-NoBuild`。

- 主界面：<http://localhost:8080/>
- 新归档文件：`data/mcp-tools/mindbridge-reports.xlsx`。首次迁移复制原文件，原文件保留。
- 工具账本：`data/mcp-tools/tool-ledger.mv.db`，重启容器保留。
- 本地配置：`.env.resend.local`，已排除在 Git 和 Docker 构建上下文之外。
- 运行配置：`docker-compose.mcp.yml`，工具服务只在 Docker 网络中监听 8081，不暴露宿主机端口。
- 主项目自动 MCP 客户端关闭，由显式 SDK 客户端管理带服务令牌的连接；主项目自身的 MCP 服务端关闭。

`start-local.ps1` 和 `start-mail-callback.ps1` 会保留已经启用的 MCP 模式。临时公网隧道 URL 改变后运行 `start-mail-callback.ps1` 更新回调。

## 可靠性边界

主项目的 MySQL 是业务任务状态来源；工具服务的 H2 文件记录外部调用结果。数据库事务不会跨越文件与邮件，采用持久化任务、有限重试和结果核对。

邮件重试固定 taskId、幂等键、首发时间及正文。工具端已记录 ACCEPTED 的任务直接返回已保存结果；未确定结果的调用仅在首发后 23 小时内复用原键请求 Resend，留出提供商 24 小时去重窗口的余量。超过安全窗口停止自动发送，转人工核对。不要删除工具账本后把旧任务当新任务发送。

工具服务当前是单实例，单写入者维护 Excel。不要将多个独立实例指向同一个工作簿。工具请求有服务令牌验证，发件人与收件人有白名单；主项目不需要 Resend API Key。

`SUCCESS/ACCEPTED` 表示提供商接受发送；`DELIVERED` 表示收件服务器接受；辅导员确认是独立状态，不由投递成功推断。

当前测试发件地址仍为 Resend 提供的测试地址，只发到已验证的账号邮箱。正式发送给其他辅导员前需完成自有域名验证并更新发件地址和收件白名单。

## 已有测试

独立服务集成测试通过真实 MCP 握手与工具调用，使用本地模拟邮件服务验证鉴权、工具发现、Excel 重复归档、邮件去重、内容冲突拒绝和投递查询，不向外部发邮件。

主项目测试覆盖固定邮件任务参数、远端投递查询，以及原有回复恢复、预警任务、回调与确认流程。真实联调记录另存 `data/mcp-live-verification.json`（完成联调后生成）。

### 2026-10-16 真实联调结果

以模拟报告验证任务链路，Excel 任务、邮件任务均为 SUCCESS。独立 MCP 服务日志确认真实归档和 Resend 提交，真实邮件标识仅保存在本地联调记录中。提供商的 `email.sent`、`email.delivered` 签名回调均已入库并应用，邮件任务更新为 DELIVERED。测试预警的后续提醒被显式停用，没有标记成辅导员已人工确认。

本次从持久化模拟任务开始验证，不将其宣称为真实学生咨询或模型风险识别效果测试。部署时发现并修正了正式 JAR 缺少 `mcp-spring-webflux` 运行依赖的问题；启动脚本现会检查带认证的 MCP 入口，防止仅凭健康接口成功就误判工具可用。
