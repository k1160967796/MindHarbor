# RouteGuard 路由校验

## 接入位置

`MemoryAgent → SupervisorAgent（IntentClassifier → RouteGuard → 提交最终路由）→ 后续 Agent`

RouteGuard 是 SupervisorAgent 使用的本地服务，不占用 Agent loop 的额外一步，不增加模型、数据库或网络调用。输入为当前脱敏消息和 MemoryAgent 已加载的 `previousHistory`，不会把模型生成的摘要当作独立证据。

## 校验逻辑

- 分类器已经返回 CONSULT / RISK：保留原判断。
- 分类器返回 CHAT，但当前消息命中现有高风险词规则：提升为 RISK。
- 当前消息表达心理困扰：提升为 CONSULT，后续由 RiskGuardianAgent 评估风险等级；并不直接认定为高风险。
- 当前消息是“那我该怎么办”“还是这样”等省略追问：倒查最多 3 条用户消息；发现咨询背景则提升为 CONSULT。忽略 assistant / system 消息，遇到不相关用户话题停止回溯。
- 普通知识解释、技术问答或明确话题切换不因旧的咨询背景升级；若当前消息同时明确表达自身困扰，仍优先保留咨询路径。

只有校验后的最终意图仍为 CHAT 时，SupervisorAgent 才会设置 `knowledgeHandled` 和 `riskAssessed`，跳过检索与风险评估。

## 运行轨迹

沿用 SupervisorAgent 的 ROUTE_INTENT 记录，例如：

```text
candidate=CHAT; intent=CONSULT; routeGuard=RECENT_CONSULT_FOLLOW_UP
```

记录原始分类、最终路由和原因码，不额外记录原文。原因码包括 `KEEP_NON_CHAT`、`CURRENT_HIGH_RISK_SIGNAL`、`CURRENT_CONSULT_SIGNAL`、`RECENT_CONSULT_FOLLOW_UP`、`NO_CONSULT_EVIDENCE`。

## 边界

这是规则兜底，不能保证理解所有隐晦表达、否定句、引用或话题变化。现有分类器的高风险词匹配仍然保留；RouteGuard 不会纠正它已作出的 CONSULT/RISK 误报。当前未实现第二次模型复核、分类置信度阈值或回复内容审核。

## 验证

`RouteGuardTests` 覆盖困扰表达、普通问答、引用、追问、历史角色隔离及话题变化；`SupervisorRouteGuardTests` 强制模型输出 CHAT，验证最终路由恢复 KnowledgeAgent / RiskGuardianAgent 的执行条件，且没有增加模型调用。`AgentLoopHarnessTests` 继续验证正常聊天、咨询、高风险三条原有链路。

使用 Java 17 执行 `mvn test`。源代码改动需要重启应用才能生效；Docker 运行方式需要重新打包、构建并更新 app 容器。
