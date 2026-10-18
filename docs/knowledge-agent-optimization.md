# KnowledgeAgent 检索链路优化

## 当前实现

1. 完整问题直接使用原文检索；有用户历史、且问题存在省略或指代时才改写。改写失败使用近期用户输入与当前问题组合。
2. BM25 先计算并保留结果。查询向量只生成一次，供 Chroma 与本地向量降级复用。未审核知识始终过滤，数据库原文作为权威来源。
3. 两路召回各最多 20 个，融合后默认取 8 个供评审，每个片段约 400 字符。存在完整查询词命中时围绕命中位置取摘要，否则取开头；这不是独立语义摘要模型。
4. 一次模型评审输出 scores（片段 ID 到分数的映射）、coverage、retryQuery，减少重复字段的生成开销；兼容原 ranked 数组格式。原始问题、查询和必要记忆共同参与评审。服务端校验 ID、重复项、完整候选集合、分数范围、覆盖状态和补查 query 格式；非法结果降级为初排，状态 UNKNOWN。
5. 仅 PARTIAL/NONE 且有不同 query、剩余预算足够时补查，最多一次。空召回允许在尚未改写时补一次改写。两轮候选按 ID 去重，以 RRF 合并名次，不直接比较跨轮分数。
6. 二次评审失败保留首轮选中资料，再补充新候选。无进展不再循环。
7. 最后取数据库完整原文及必要邻接片段，默认最多 4 个结果。覆盖状态和降级原因传给 CounselorAgent，并写入 KnowledgeAgent 轨迹。

正常完整问题且资料充分时，生成式模型评审一次；省略追问通常为改写一次加评审一次；二次检索最多增加一次评审。Embedding 不计入生成式模型调用次数。

## 时间预算与模型协议

| 配置 | 默认值 |
|---|---:|
| RAG_BUDGET_MS | 4000 |
| RAG_REWRITE_TIMEOUT_MS | 800 |
| RAG_REVIEW_TIMEOUT_MS | 3500 |
| RAG_EMBEDDING_TIMEOUT_MS | 1200 |
| RAG_CHROMA_TIMEOUT_MS | 800 |
| RAG_RETRY_MIN_REMAINING_MS | 1200 |
| RAG_RECALL_CANDIDATE_LIMIT | 20 |
| RAG_RERANKER_CANDIDATE_LIMIT | 8 |
| RAG_RERANKER_MAX_CONTENT_CHARS | 400 |
| RAG_TOP_K | 4 |

所有步骤共用单调时钟 deadline，外部调用采用 min(剩余预算, 本步骤上限)。模型使用流式接口聚合内部输出，超时取消订阅；Embedding、Chroma 的 WebClient 调用同样设置总超时。集合初始化与 Chroma 查询也共用一次网络超时。

这不是整个 HTTP 请求的 4 秒 SLA：数据库查询、CPU 排序、最终原文读取可能使总耗时略超预算；其余 Agent、用户画像抽取及最终回答生成也不在此预算内。远端服务器是否立即停止计算取决于服务端对断连的处理。

本地 Ollama 评审通过 format 传入 JSON Schema；OpenAI 兼容服务走提示词格式要求与相同后端校验，不假定所有兼容端点都支持严格 Schema。分类、风险评估和最终流式回复的原调用方式保持不变。

本机 docker-compose.local.yml 将评审片段上限设为 200 字符：实测 400 字符配置在 qwen2.5:1.5b 上出现 3500ms 评审超时。候选数仍为 8，最终回答使用回读的完整片段。短摘要可能遗漏相关内容，仍需用标注问题集验证检索质量。

## 轨迹与回复边界

KnowledgeAgent 记录 rewritten、retry、coverage、degraded、rewriteMs、recallMs、reviewMs、elapsedMs。前三个分段耗时对应首轮，总耗时包含补查及原文读取。

- SUFFICIENT：模型判断选中的资料足以支持本次回答，不是经过校准的正确率承诺。
- PARTIAL / NONE：部分覆盖 / 没有相关资料。
- UNKNOWN：评审关闭、超时、格式异常或资料在读取时发生变化。
- 超时、普通错误及格式错误分别记录，如 REVIEW_TIMEOUT、REVIEW_FAILED、REVIEW_INVALID_JSON；预算耗尽为 BUDGET_EXHAUSTED。

资料不足或 UNKNOWN 时不能编造知识来源、机构流程。风险评估独立执行，检索失败不降低安全回应要求。模型生成的 query 仅用于检索，不写作用户事实，也不改变路由或风险等级。

## 验证

重点测试：完整问题单次模型调用、上下文追问改写、二次检索合并去重、无第三轮、二次评审失败保留首轮、无预算跳过补查、非法模型 ID/分数/状态、Chroma 失败复用向量、Embedding 失败保留 BM25、未审核资料隔离，以及模型/Embedding/Chroma 超时取消上游订阅。

延迟与质量需用同一组问题继续评估。冷启动时较短预算可能触发降级，应结合模型预热、实际轨迹与资料覆盖率调整参数。

### 2026-10-13 本机验证

- Java 全套测试 80 项通过，失败和错误均为 0；Docker 中 JAR 的 SHA-256 与本次构建一致。
- app、MySQL、Redis、Chroma、document-parser、Mailpit 六个容器运行，应用健康检查 UP。
- 真实 SSE 请求“最近考试压力很大，晚上睡不着，有什么缓解方法？”：KnowledgeAgent 总耗时 2369ms，其中召回 121ms、联合评审 2225ms；coverage=SUFFICIENT，无降级，保留 4 个结果，未改写、未补查。
- 同次请求首字 11469ms，总耗时 13235ms，收到 done，未收到 error。上述记录在 target/knowledge-live-local200.json。
- 这是单次功能与耗时检查，不是正式性能基准或召回质量实验。没有改动前同条件基准，不能据此声称整体响应改善比例；其他 Agent 和最终生成仍有明显耗时。

本次 Docker 启动弹窗来自遗留 IPC socket 文件导致启动失败。停止 Docker 后将本地运行目录备份并重建，随后正常启动；未重置 Docker，也未删除数据库卷。
