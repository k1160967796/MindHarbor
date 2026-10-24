# 模型调用观测与 Token 统计

## 查看入口

- 管理员后台 → **模型调用观测**：最近 100 次已落库的调用，点击刷新更新。
- 打开某轮运行轨迹 → **刷新本轮模型调用**：按 requestId 查询，包含该请求的重试及重新生成。
- `GET /api/admin/model-calls`：最近 100 次；`?requestId=...` 查询指定请求。
- 管理员访问 `/actuator/metrics`：Micrometer 实时指标；匿名和学生无权访问。

旧轨迹没有 requestId，不能追补历史用量。调用记录异步写入，刚结束时可能需要稍后刷新；
后台列表也能看到准备失败、尚未产生完整 Agent 轨迹的模型调用。

## 采集范围与关联方式

当前使用 Spring AI **1.0.0**。手动构建 OllamaChatModel / OpenAiChatModel 时显式传入
ObservationRegistry，启用框架原生模型观测。同时在统一 SpringAiChatClient 中读取 ChatResponse 元数据，
为业务后台记录每次调用。额外覆盖项目自定义的 OpenAI 兼容 Embedding HTTP 客户端。

| 字段 | 含义 |
| --- | --- |
| id | 每次统一客户端调用独立 UUID；业务层重新调用会产生新记录，适配器内部的 HTTP 重试不单独拆分 |
| requestId / sessionId | 一轮学生请求及会话；非聊天调用可为空 |
| phase | MEMORY_AGENT、SUPERVISOR_AGENT、KNOWLEDGE_AGENT、RISK_GUARDIAN_AGENT 等；画像写入为 PROFILE_MEMORY，最终回答为 FINAL_RESPONSE |
| generation / attempt | ReplyStore 的生成轮次和尝试次数；准备阶段 attempt=0 |
| operation | CHAT_SYNC、CHAT_INTERNAL、CHAT_STREAM 或 EMBEDDING |
| provider / model | 适配器类别及实际响应模型（无响应名称时使用配置名称）；openai-compatible 表示接口协议，可能是本地 Ollama |
| durationMs | 客户端开始调用至完成、异常或取消的时间，包含网络等待 |
| firstTokenMs | 流式响应首次非空文本片段时间；同步调用、Embedding、尚未产生文本时为空 |
| outcome | SUCCESS、EMPTY、ERROR、TIMEOUT、CANCELLED、TRUNCATED |
| finishReason / truncated | 服务商结束原因及是否明确达到长度限制；未提供时为 null，不能推断为完整 |
| inputTokens / outputTokens / totalTokens | 服务商通过响应元数据返回的用量，缺失为 null |
| usageSource | PROVIDER 或 UNAVAILABLE，不做字符数估算 |

首文本片段不保证恰好一个 token，也不是“学生发送到看到首字”的全链路时间。
多个调用耗时相加仅表示累计调用耗时，并发时不等于整轮墙钟时间。
图片视觉解析、OCR、MCP 工具与完整 Agent 节点耗时不在本次模型调用表的采集范围内。

同步准备阶段使用可关闭的 ThreadLocal 作用域，Agent 完成或异常时恢复旧作用域。
最终 Flux 在订阅时从 Reactor Context 获取请求标识，每次重试带入新的 attempt，避免跨线程、跨请求混淆。
新运行轨迹增加可空 requestId，与调用记录关联；数据库通过现有 JPA update 策略创建 model_call_records 表。

## 用量准确性与失败边界

1. 保留流结束时可能没有正文、但包含 usage 的帧。重复累计用量只更新快照，不按帧相加。
2. Spring AI 对部分无用量响应会产生零值占位。聊天调用没有非空用量时保守记为 UNAVAILABLE，
   不把它当作“实际消耗为零”。Ollama 是否返回用量由实际响应决定。
3. OpenAI 默认开启 stream_options.include_usage。兼容服务不支持时可设置
   `AI_OPENAI_STREAM_USAGE=false`；相应缺失用量显示“未提供”，不能保证流式 Token 统计完整。
4. 中断、失败的请求可能已消耗模型资源，却没有收到最终 usage。因此汇总包含**已知用量与缺失次数**，
   不能把已知总计当作完整账单。服务商返回的部分用量保留，状态仍显示失败或取消。
5. 内部 completeWithin 总超时记录 TIMEOUT；上层回复超时取消或浏览器取消传递到流客户端时，
   客户端只能可靠观察到 CANCELLED，不把它臆断为用户主动取消。
6. 没有价格表，不计算货币金额。Token 不等于费用，本地推理也有硬件和运行成本。

## 指标与持久化

Micrometer 自定义指标：

- `mindharbor.model.call`：调用数和耗时 Timer，配置 P50/P95/P99。
- `mindharbor.model.first.token`：首次文本片段时间 Timer，配置 P50/P95/P99。
- `mindharbor.model.tokens.input/output/total`：已知 Token Counter。
- `mindharbor.model.usage.missing`：用量未知的调用数。
- `mindharbor.model.persistence.queue`：异步入库待写数量。
- `mindharbor.model.persistence.dropped`：队列满、数据库失败等导致未保存的观测数。

指标标签只有受代码控制的 provider、phase、operation、outcome，不放用户、请求 ID、正文或服务商错误消息。
框架原生指标和本项目指标是同一调用的不同视图，不能相加当作总调用量。
Actuator 指标保存在当前进程中，重启后重置；数据库中的调用明细仍保留。
本次未配置 Prometheus 或外部 trace collector；对外导出需单独配置并保持管理接口鉴权。

数据库写入使用单独线程和容量 1000 的有界队列，不在 SSE/Netty 回调线程执行 JDBC。
数据库异常或队列满不阻断学生回答，会增加丢弃计数并记录不含正文的警告。
正常停机最多等待 5 秒排空队列；强制退出、进程崩溃或存储故障可能丢失尚未保存的观测，
这不是计费用的强一致审计账本。长期运行需要按业务保留期限另行归档，当前不自动删除数据。

## 验证

实机 MySQL 验证发现旧版 `@Lob` 默认长度可能生成 `tinytext`，使较长轨迹入库失败并阻断回答。
`agent_run_trace_steps.observation`、`agent_run_traces.input` 和 `user_memory_items.summary`
已显式声明 `LONGTEXT`。升级前应备份数据库；使用当前 JPA update 部署策略后，检查实际列类型已扩容。

`ModelCallTelemetryTests` 验证流式尾帧、累计 usage 去重、缺失用量、重试、异常脱敏、取消、超时、
并发上下文隔离、空响应以及数据库异常不影响回答。
`ModelObservationIntegrationTests` 验证真实 H2 持久化、管理员查询汇总、学生/匿名接口隔离。
`SpringAiProviderUsageTests` 使用本地模拟 HTTP 服务和真实 Spring AI 1.0.0 适配器，验证 OpenAI
流式 usage 尾帧、Ollama 最终用量帧以及 Embedding 用量缺失的处理。
既有 `KnowledgeNetworkCancellationTests`、`ReplyRecoveryTests`、`ApiSseHarnessTests` 和 Agent 流程测试
用于检查观测没有破坏取消、回复恢复与原有行为。测试不发送真实预警邮件。
