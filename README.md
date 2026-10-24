# MindHarbor（心港）

面向校园心理陪伴场景的 Java 智能体项目，使用 Spring Boot、Spring AI 和原生 HTML/CSS/JavaScript，提供多 Agent 路由、RAG、SSE 流式对话、用户画像记忆和后台预警任务。

项目用于工程演示与研究，不替代专业心理咨询、诊断或紧急救援。本文描述的是仓库当前实现，不代表已部署的公共服务或经临床验证的效果。

## 已实现的能力

| 能力 | 当前实现 |
| --- | --- |
| 意图路由 | 区分 `CHAT / CONSULT / RISK`；CHAT 放行前进行 RouteGuard 复核，覆盖个人困扰、咨询追问和近期危险表达 |
| 多 Agent | 记忆读取、主控路由、知识检索、风险守护、陪伴或咨询回复规划；Agent loop 最多 8 步 |
| 流式回复 | SSE 输出；可重试的生成失败最多 3 次总尝试；支持按请求 ID 查询、恢复、重新生成及长度截断后的有限续写 |
| 检索增强 | BM25、可选向量召回、query 改写、候选评审与重排；资源不可用时保留检索降级路径 |
| 标签候选缓存 | 将背景、情绪、表现及同义表达归一为标签，复用公共候选片段；命中后仍进行相关性评审 |
| 已审核知识包 | 管理员审核后，在明确适用范围内直接复用知识块，跳过召回与候选评审；仍执行风险评估 |
| 对话与画像记忆 | H2/MySQL 保存持久记录，Redis 缓存近期对话，Chroma 可用于画像语义召回 |
| 风险报告与工具任务 | 咨询/风险分支按规则生成后台报告；Excel 与高风险邮件是独立持久化任务，学生回复不等待工具执行 |
| 邮件跟进 | 支持日志、SMTP 以及独立 MCP/Resend 模式；区分发送受理、投递和辅导员主动确认 |
| 知识库管理 | 管理员录入文本、上传文件；可选 OCR/视觉解析与图片内容校对、发布 |
| 管理后台与评测 | 对话记录、报告、工具状态和 Agent 执行轨迹；Java 评测导出及可选 RAGAS 工具 |
| 模型调用观测 | 按请求及 Agent 关联聊天与 Embedding 调用；记录耗时、流式首片段时间、服务商 Token、失败与重试，管理员查看明细及指标 |
| 后台记忆维护 | 长期画像和增量摘要通过持久化任务更新；聊天前台复用已完成摘要，保留近期原文；任务租约、重试和版本校验防止旧结果覆盖新状态 |
| 事实变化管理 | 画像按属性和背景更新；保留变化依据，区分历史、当前、待确认和过期；召回校验有效性，明确表达支持规则兜底 |
| Agent 检查点恢复 | 每轮请求保存一份上下文快照，逐步骤更新结果；支持步骤超时重试、租约接管及准备阶段恢复 |
| 本地向量模型预热 | 对同源 Ollama Embedding 接口执行异步预热，并在请求失败后限频触发预热 |

项目名称、Java 包名 `com.mindharbor`、`mindharbor-*` 构建产物、模型标签及 `MINDHARBOR_*` 配置名已统一。已有实例升级前需按下方的迁移说明核对持久化数据与部署配置。

## 首次运行：公共模型 + H2

先跑通文本聊天，再按需接入 MySQL、向量检索、图片解析或真实邮件。本节不需要项目微调权重、OpenAI Key 或 Resend Key。

### 1. 准备环境

| 软件 | 用途 |
| --- | --- |
| JDK 17 | 编译与运行 Java 主应用；确认 `JAVA_HOME` 和 Maven 使用同一 JDK |
| Maven 3.9+ | 构建与测试；仓库不附带 Maven wrapper 或 `.tools/` 工具包 |
| Ollama | 本地聊天模型；可使用已安装模型覆盖下方示例 |
| Docker Compose | 推荐用于启动 Redis；已有 Redis 可复用，没有 Docker 时可使用下文的降级运行方式 |

下载或克隆仓库后，进入包含 `pom.xml` 的目录。ZIP 解压目录可能叫 `MindHarbor-main`，不必重命名本机目录。

```bash
java -version
mvn -version
ollama --version
docker compose version
```

`java -version` 与 `mvn -version` 均应显示 JDK 17。Windows 若仍使用 Java 8，先将 `JAVA_HOME` 指向自己的 JDK 17 安装目录，并更新当前终端的 `PATH`；旧版 Java 无法编译项目使用的 record 和文本块。

### 2. 启动 Redis 和模型服务

```bash
docker compose up -d redis
ollama pull qwen2.5:1.5b
```

如果暂不使用 Docker，跳过 Redis 启动命令，并使用下一节的降级运行方式。如果 Ollama 服务尚未运行，在另一个终端执行 `ollama serve`。下载模型不会自动下载本项目的 LoRA 权重；`qwen2.5:1.5b` 在这里仅用于跑通流程，其效果不等于项目微调模型的效果。

### 3. 启动应用

**Windows PowerShell：**

```powershell
$env:AI_PROVIDER = 'ollama'
$env:OLLAMA_MODEL = 'qwen2.5:1.5b'
$env:USE_CHROMA = 'false'
$env:MEMORY_USE_CHROMA = 'false'
$env:MCP_SERVER_ENABLED = 'false'
mvn spring-boot:run
```

**macOS / Linux Bash：**

```bash
AI_PROVIDER=ollama \
OLLAMA_MODEL=qwen2.5:1.5b \
USE_CHROMA=false \
MEMORY_USE_CHROMA=false \
MCP_SERVER_ENABLED=false \
mvn spring-boot:run
```

此模式使用 `data/mindharbor.mv.db` 保存 H2 数据，Redis 可用时缓存近期对话；没有 embedding 配置时，知识检索使用本地 BM25。默认邮件模式为 `log`，不发送真实邮件。环境变量示例按新终端、未额外配置其他服务的情况编写；已有环境变量会影响最终配置。

没有运行 Redis 时，保持上述环境变量，将最后的 Maven 命令替换为：

```bash
mvn spring-boot:run "-Dspring-boot.run.arguments=--management.health.redis.enabled=false"
```

这只关闭 Redis 健康探针，不会启用缓存，也不会伪造 Redis 可用状态。缓存读写失败时按代码逻辑降级，近期对话从持久化聊天记录恢复；仍会尝试连接 Redis，可能增加等待时间。正式使用建议配置可用的 Redis。

访问 <http://localhost:8080/>，首次空数据库会创建：

| 账号 | 密码 | 用途 |
| --- | --- | --- |
| `student` | `student123` | 学生聊天与个人记忆 |
| `admin` | `admin123` | 管理后台；不能以管理员身份发起学生对话 |

演示账号仅在数据库没有用户时初始化。修改初始化代码不会自动更新已有账号密码。

### 4. 检查运行结果

PowerShell 可用：

```powershell
Invoke-RestMethod 'http://localhost:8080/actuator/health'
```

Bash 可用：

```bash
curl http://localhost:8080/actuator/health
curl -u student:student123 http://localhost:8080/api/agent/status
```

健康状态 `UP` 表示相关健康检查通过；`/api/agent/status` 返回的是模型客户端配置，均不代替真实模型调用。请用学生账号发送一条普通消息，确认收到完整回答。Redis 不可用时近期记忆会降级，但健康检查仍可能为 `DOWN`；先检查 Redis 端口和连接参数。

## 架构与数据流

```text
浏览器 / API
  -> ChatController -> ChatService / ReplyStore
  -> AgentRuntimeService / AgentCheckpointStore（逐步骤保存上下文与结果）
  -> MemoryAgent -> SupervisorAgent / RouteGuard
                     -> KnowledgeAgent / RiskGuardianAgent（按路由执行）
                     -> CompanionAgent 或 CounselorAgent
  -> Spring AI -> Ollama 或 OpenAI 兼容接口 -> SSE

回复准备完成 -> 画像、增量摘要与索引的持久化后台任务

报告落库 -> 持久化任务
           ├─ Excel 归档
           └─ 高风险邮件 -> 投递事件核对 -> 辅导员主动确认
```

`AgentRuntimeService` 调度有限步 Agent loop；`ChatService` 组织对话与流式生成；`ReplyStore` 管理请求身份、执行权、重试和完整回复保存。后台报告及轨迹包含评估信息，学生回复不展示风险等级或后台报告字段。

Excel 与邮件分别领取、执行和更新状态，**邮件不等待 Excel 成功**。数据库、Redis、工作簿和邮件服务并非同一个事务；持久化任务、重试和结果核对也不等于跨系统原子提交或严格只发送一次。

```text
src/main/java/com/mindharbor/agent/
  config/          配置、认证、模型与 MCP 接入
  controller/      聊天、知识库、报告、邮件与记忆 API
  domain/          持久化实体与状态
  repository/      Spring Data JPA
  service/
    agent/         多 Agent 路由与回复规划
    ai/            模型客户端、提示词与风险词库
    knowledge/     入库、检索、重排、文档解析与评测
    memory/        近期对话与用户画像
    mcp/           Excel、邮件及 MCP 工具调用
src/main/resources/static/   浏览器界面
src/main/resources/knowledge/ 内置知识库
services/mcp-tools/          独立 MCP 工具服务
services/document-parser/    可选 OCR 文档服务
scripts/                    启动、打包及模型导入脚本
eval/                       可选 Python RAGAS 工具
docs/                       专题设计与接入说明
```

## 模型与检索配置

### 公共 Ollama 模型与项目微调模型

首次运行可使用上文的公共模型，也可将 `OLLAMA_MODEL` 设置为自己已安装的模型标签。仓库默认值仍是 `mindharbor-qwen2.5-7b-ft:latest`，它**不是克隆仓库后自动获得的模型**。

GitHub 源码不包含 GGUF、LoRA adapter、训练数据集、数据库、业务报告、私有配置或 `.tools/`。若有自行准备的项目 GGUF，将其放至：

```text
models/mindharbor-qwen2.5-7b-ft/mindharbor-qwen2.5-7b-ft-q4_k_m.gguf
```

然后运行：

```bash
bash scripts/create-finetuned-model.sh
bash scripts/run-dev.sh
```

Shell 脚本示例使用 `bash` 显式执行，不依赖克隆后脚本的执行权限。`run-dev.sh` 是 Ollama 路线的辅助脚本，会处理服务与模型检查；它不会自动启动 Redis 或完整的外部依赖。训练与转换步骤见 [微调指南](docs/qwen25-7b-lora-finetune-guide.md)，该指南所需训练数据须另行准备。

### OpenAI 兼容聊天接口

主应用支持 `AI_PROVIDER=openai`，读取 `OPENAI_API_KEY`、`OPENAI_MODEL` 和 `OPENAI_BASE_URL`。例如 Bash：

```bash
AI_PROVIDER=openai \
OPENAI_API_KEY='replace-with-your-key' \
USE_CHROMA=false \
MEMORY_USE_CHROMA=false \
MCP_SERVER_ENABLED=false \
mvn spring-boot:run
```

PowerShell 使用 `$env:变量名 = '值'` 设置相同变量。密钥只放本机环境或部署平台的秘密配置，不写进 README、源码或提交记录。不要用 `run-dev.sh` 切换 OpenAI 模式，该脚本会显式选择 Ollama。

### 向量检索不是只启动 Chroma

默认 YAML 中 `USE_CHROMA=true`，`MEMORY_USE_CHROMA` 默认跟随它；首次运行示例显式关闭两者，以避免误把未配置的向量服务当作可用依赖。

知识库向量召回需要同时具备：可用的 embedding 接口、对应向量数据和 Chroma 服务。当前 embedding 客户端使用 OpenAI 兼容的 `/v1/embeddings`，配置来自 `OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_EMBEDDING_MODEL`。Key 为空时返回空向量；知识检索仍可使用 BM25，但不应声称已启用向量召回。

本地容器配置使用 Ollama 兼容接口与 `bge-m3`，并设置非空占位 Key `ollama-local`；它不是外部服务密钥。聊天模型仍由 `AI_PROVIDER` 和 `OLLAMA_*` 控制。`OPENAI_BASE_URL` 同时影响 OpenAI 聊天与 embedding，混用外部聊天模型和本地 embedding 前需核对两者的实际地址配置。

详细实现见 [RAG 技术说明](docs/rag-technical-guide.md)。

### 标签缓存与已审核知识包

知识复用分为两层，均不缓存学生的最终回答：

1. **候选缓存**：按“背景 + 情绪 + 表现”匹配标准标签。同义表达可归为同一标签，例如“睡不着 / 难以入睡”。命中后重新读取并核验知识块，跳过重复召回，但仍评审其相关性和充分性。
2. **已审核知识包**：管理员在“常见问题知识包”中核对实际知识原文并启用后，符合严格适用范围的请求可跳过召回与评审。审核针对知识包及其版本，**不是每次对话都需要批准**。

当前标签目录有 **12 组**：8 组支持审核后直用，另 4 组仍需逐次评审。标签覆盖考试、学业任务、课堂展示、同学沟通、实习申请等场景；配置位于 `src/main/resources/knowledge-tags/catalog.json`。

```text
咨询请求 -> 标签及适用范围检查
  ├─ 有效的已审核知识包 -> 复用已发布原文 -> RiskGuardian
  └─ 无匹配 / 未审核 / 已失效 -> 候选缓存或正常召回 -> 评审 -> RiskGuardian
RiskGuardian 判断为中高风险 -> 撤销直用知识包 -> 正常知识检索与评审
```

否定、背景冲突、多种表现、危机信号等不满足规则时会绕过直用。知识包最多包含 4 块、6000 字符；管理员审批有效期为 30 天，Redis 缓存默认 1800 秒。数据库审批状态与知识来源内容均会重新核验，禁用、过期、来源改变或不再发布后不能继续直用旧包。普通候选缓存也会在知识更新后失效。

新部署不会自动批准知识包。登录管理员页面，打开“常见问题知识包”，核对适用范围和来源内容后再启用。运行轨迹中的 `SKIPPED_APPROVED_PACK` 表示这次跳过了知识评审，**不表示跳过风险评估**。

详细边界见 [标签缓存](docs/knowledge-tag-cache.md) 和 [知识包审核](docs/reviewed-knowledge-packs.md)。

## Docker 与扩展功能

### 基础容器模式

先保证宿主机 Ollama 可被容器访问，并准备好所选模型。Bash 示例：

```bash
OLLAMA_MODEL=qwen2.5:1.5b docker compose up -d mysql redis chroma mailpit
OLLAMA_MODEL=qwen2.5:1.5b docker compose up -d --build app
docker compose ps
docker compose logs -f app
```

PowerShell 先设置 `$env:OLLAMA_MODEL = 'qwen2.5:1.5b'`，再运行相同的 `docker compose` 命令。基础 Compose 使用 MySQL；启动依赖的命令返回不等于数据库已就绪，需检查日志和应用健康状态，必要时在依赖就绪后重启应用。

| 服务 | 基础 Compose 的宿主机端口 |
| --- | --- |
| 应用 | `8080` |
| MySQL | `3306` |
| Redis | `6379` |
| Chroma | `8000` |
| Mailpit SMTP / 页面 | `1025` / `8025` |

基础 Compose 的邮件模式默认仍为 `log`；启动 Mailpit 本身不会切换为 SMTP。应用容器通过 `host.docker.internal:11434` 访问宿主机 Ollama，需按实际系统配置服务监听地址与容器网络访问。普通文本聊天不要求启用 OCR。

停止容器、保留数据卷：`docker compose down`。重置前先备份，不要把删除数据卷作为常规排障步骤。

### Windows 本地完整演示

```powershell
.\scripts\start-local.ps1
```

此脚本组合基础、图片解析和本地 Compose 文件，会构建 JAR/容器，准备 `qwen2.5:1.5b`、`qwen2.5vl:3b`、`bge-m3`，启用 OCR/视觉解析和本地向量检索，并使用 SMTP/Mailpit 演示。首次模型与 OCR 依赖下载可能耗时，资源需求也高于最小文本模式。

本地覆盖配置将应用绑定到 `127.0.0.1:8080`，MySQL 绑定到 `127.0.0.1:3307`；Redis、Chroma 和 Mailpit 端口仍由基础 Compose 决定，不能据此认为所有依赖均已限制为本机访问。该覆盖文件使用 `!override`，需使用支持该标签的 Docker Compose。

脚本会读取已有 `.env.resend.local`；如果其中启用了 MCP，会保留真实邮件模式。仅演示聊天时不要复制真实邮件配置。已有完整构建可使用 `-NoBuild`，源代码变更后需要重新构建。

### 图片与扫描 PDF

普通文本模式支持 Markdown、TXT 和文本 PDF；图片和扫描 PDF 的 OCR/视觉路径需启用 `KNOWLEDGE_MEDIA_ENABLED` 并启动 `services/document-parser`。当前上传入口限制为 **10 MiB**；解析结果可能需要管理员在知识库校对界面确认后才参与检索。

启用说明与解析限制见 [图片知识库接入说明](docs/image-knowledge-ingestion.md)。

### 真实邮件与独立 MCP

默认 `MAIL_PROVIDER=legacy`，搭配 `MCP_EMAIL_MODE=log` 不发送真实邮件；`smtp` 使用配置的 SMTP 服务。独立工具链由 `docker-compose.mcp.yml` 启用：

```text
主应用 -> 带服务令牌的 MCP Client -> mcp-tools
  ├─ mindharbor_write_excel_report
  ├─ mindharbor_send_risk_alert -> Resend
  └─ mindharbor_get_mail_status
```

公开仓库提供 [.env.example](.env.example) 作为字段说明。仅在准备接入真实邮件时，将其复制为 `.env.resend.local` 并在本机补齐配置；Spring Boot 本地 Maven 启动不会自动读取此文件。

独立工具模式至少需要 `RESEND_API_KEY`、`ALERT_MAIL_FROM`、`ALERT_MAIL_RECIPIENTS`、`ALERT_RECIPIENT_ACCOUNTS` 和强随机 `MCP_SERVICE_TOKEN`。接收投递回调还需 `RESEND_WEBHOOK_SECRET` 及服务商可达的回调地址；邮件链接通过 `MINDHARBOR_PUBLIC_URL` 指向应用。`localhost` 只适合本机点击测试。

Windows 的 `scripts/start-mcp.ps1` 会在 Token 配置项不存在时生成令牌，开启 MCP 模式并处理已有持久化任务；不要在未检查配置和待处理任务时运行。完整接入步骤见 [独立 MCP 工具说明](docs/mcp-tools-integration.md) 与 [邮件跟进流程](docs/mail-workflow.md)。

Excel 和邮件独立执行；`SUCCESS / ACCEPTED`、`DELIVERED`、辅导员主动确认是不同状态，不能互相替代。工具端账本、幂等窗口和文件单写入者的限制见上述专题文档。

## 配置速查

以下是 `src/main/resources/application.yml` 的默认值；Compose 覆盖文件和显式环境变量可能改变它们。

| 环境变量 | 默认值 / 说明 |
| --- | --- |
| `AI_PROVIDER` | `ollama`；也支持 `openai` |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `mindharbor-qwen2.5-7b-ft:latest` |
| `OPENAI_BASE_URL` / `OPENAI_MODEL` | `https://api.openai.com` / `gpt-4o-mini` |
| `OPENAI_API_KEY` | 空；启用 OpenAI 聊天时必须提供 |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small`；使用上面的兼容接口地址和 Key |
| `SERVER_PORT` | `8080` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | H2 文件库 / `sa` / 空；MySQL profile 另见 `application-mysql.yml` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / 空 |
| `USE_CHROMA` / `MEMORY_USE_CHROMA` | 默认 `true`；后者未设置时跟随前者 |
| `CHROMA_BASE_URL` | `http://localhost:8000` |
| `MCP_SERVER_ENABLED` / `MCP_CLIENT_ENABLED` | 主应用内置服务端默认 `true`，自动客户端默认 `false` |
| `MCP_EXCEL_MODE` / `MCP_EMAIL_MODE` | `local` / `log` |
| `MAIL_PROVIDER` | `legacy`；可选直接 `resend` 或独立工具 `mcp-resend` |
| `KNOWLEDGE_MEDIA_ENABLED` | `false` |
| `KNOWLEDGE_TAG_CACHE_ENABLED` | `true`；普通标签候选缓存开关 |
| `KNOWLEDGE_TAG_CACHE_TTL_SECONDS` | `1800` 秒 |
| `KNOWLEDGE_REVIEWED_PACKS_ENABLED` | `true`；功能开关，知识包仍须管理员逐包审核 |
| `RAG_EVAL_ENABLED` | `false` |
| `CHAT_ANSWER_MAX_TOKENS` / `CHAT_CONTINUATION_MAX_TOKENS` | 最终回答 1024 / 单次续写 512 Token |
| `AGENT_PREPARATION_SECONDS` / `AGENT_STEP_TIMEOUT_MS` | 准备阶段预算 300 秒 / 单步超时 30000 毫秒 |
| `AGENT_RETRY_DELAY_MS` / `AGENT_RECOVERY_ENABLED` | 重试基础间隔 500 毫秒 / 自动恢复默认 `true` |
| `MEMORY_BACKGROUND_ENABLED` / `MEMORY_BACKGROUND_IDLE_MS` | 后台记忆默认 `true` / 前台空闲等待 2000 毫秒 |
| `MEMORY_SUMMARY_MESSAGE_THRESHOLD` / `MEMORY_SUMMARY_CHAR_THRESHOLD` | 增量摘要阈值 6 条消息 / 3000 字符 |
| `MEMORY_STATE_VALID_DAYS` / `MEMORY_CONTEXT_VALID_DAYS` | 状态类记忆 14 天 / 其他非稳定背景 90 天 |
| `AI_OPENAI_STREAM_USAGE` | `true`；请求兼容接口返回流式 Token 用量 |
| `EMBEDDING_LOCAL_WARMUP_ENABLED` | `true`；同源本地 Embedding 异步预热 |

例如公共应用地址使用 `MINDHARBOR_PUBLIC_URL`，知识索引重建开关使用 `MINDHARBOR_KNOWLEDGE_REINDEX_ON_START`。环境变量和 Java 配置前缀均以当前配置文件为准，其他参数见配置文件和 `docs/`。

## API 与回复恢复

以下 curl 示例使用 Bash 语法；Windows 可通过网页操作，或在 Git Bash 中执行。接口使用 HTTP Basic；除健康检查等公开入口外，业务 API 需要登录，管理员接口还需相应角色。

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好，可以帮我安排今天的学习吗？"}' \
  http://localhost:8080/api/chat/stream
```

集成客户端应在提交前生成并保存 `requestId`，推荐 UUID。格式为 16–64 位字母、数字或连字符；`message` 最长 4000 字符，`sessionId` 留空创建会话。同一请求 ID 用于恢复同一轮请求，新消息使用新的 ID。

| 接口 | 用途 |
| --- | --- |
| `POST /api/chat/stream` | 提交消息并接收 SSE |
| `GET /api/chat/replies/{requestId}` | 查询生成状态和已成功保存的完整正文 |
| `POST /api/chat/replies/{requestId}/resume` | 恢复原请求 |
| `POST /api/chat/replies/{requestId}/regenerate?generation=N` | 显式重新生成失败回复；`N` 使用状态查询返回的轮次 |
| `GET /api/profile/memory` | 当前账号画像记忆 |
| `GET /api/profile/memory/{id}/changes` | 本人记忆的事实变化、来源和版本关系 |
| `GET /api/admin/reports` | 管理员查看心理报告 |
| `GET /api/admin/run-traces` | 管理员查看 Agent 轨迹 |
| `GET /api/admin/model-calls?requestId=...` | 按请求查看模型调用、耗时、Token 和结束状态 |
| `GET /api/admin/agent-checkpoints` | 查看 Agent 准备阶段检查点与执行进度 |
| `GET /api/admin/memory-tasks` | 查看画像、摘要、索引后台任务 |
| `POST /api/admin/memory-tasks/{id}/retry` | 重试失败的后台记忆任务 |
| `POST /api/admin/knowledge` | JSON 录入知识，字段为 `source`、`content` |
| `POST /api/admin/knowledge/file` | multipart 文件上传，字段名为 `file` |
| `GET /api/admin/knowledge/review` | 待校对知识块 |
| `PUT /api/admin/knowledge/review/{id}` | 校对并发布知识块 |
| `GET /api/admin/knowledge/packs` | 查看知识包范围、来源、审批状态与预览摘要 |
| `PUT /api/admin/knowledge/packs/{id}/approve` | 管理员提交当前预览的 `digest`，批准对应版本 |
| `PUT /api/admin/knowledge/packs/{id}/disable` | 禁用知识包直用 |

SSE 包含 `meta`、`start`、`token`、`retry`、`heartbeat`、`done`、`error`、`busy` 等事件。**只有 `done` 或查询到 `SUCCESS` 才表示回复成功**，不能把连接关闭当成成功。

生成失败或中断不保存半段助手正文。自动重试复用已准备的提示词，恢复是重新生成整段回答，不是继续拼接旧 token；准备阶段可从已提交的 Agent 检查点恢复；恢复预算耗尽或快照版本不兼容时需要重新提交。完整边界见 [回复恢复说明](docs/reply-recovery.md)。

## 新增功能

### 模型调用观测

聊天、内部结构化调用和 Embedding 调用统一关联请求与 Agent 阶段，记录模型名称、调用次数、耗时、首片段时间、输入与输出 Token、重试、结束原因及截断状态。管理员可查看最近调用和单个请求的明细，并通过 Actuator 读取指标。模型未返回的用量显示为“未提供”。

### 回答长度与截断续写

最终回答默认目标为 150～250 字，输出上限独立配置为 1024 Token。模型明确因长度上限结束时，支持最多一次、512 Token 的补充续写。续写次数持久化保存；仍不完整的回复保留失败状态，不写入正常会话记忆。

### 后台记忆与增量摘要

长期画像、会话摘要和向量索引通过持久化任务在后台更新，支持执行租约、有限重试和任务状态查询。MemoryAgent 复用已经完成的摘要与近期原文；摘要按新增消息数量或字符阈值增量更新，明显话题冲突时回退到近期原文。管理员可查看后台任务并重试失败任务。

### Agent 检查点与步骤恢复

以 requestId 保存一份 AgentContextSnapshot，每个步骤成功后更新同一条快照。快照包含历史、意图、知识片段、风险评估及回复策略；恢复时从已提交状态继续。步骤记录包含执行状态、尝试次数、耗时与错误类型，支持租约接管、单步超时和有限重试。

### 长期记忆事实管理

画像按属性与背景组织，记录来源消息、陈述时间、有效期和版本，区分当前有效、历史、待确认、过期与归档状态。新事实更新同一属性的当前值并保留变化依据；召回过滤失效或旧版本索引，用户可查看事实变化并删除自己的记忆。

### 路由保护与本地模型预热

普通任务快捷规则限定为具体任务领域；CHAT 候选通过 RouteGuard 检查个人困扰、咨询追问和近期危险表达。咨询与风险分支继续执行 RiskGuardian，路由复核本身不调用额外模型。同源本地 Embedding 支持异步、限频预热。

## 部署边界与排障

### 已有部署的名称迁移

本次统一名称涉及 Java 包、配置前缀、数据库默认名、H2/Excel 文件名、Chroma collection、Redis key、浏览器缓存 key、MCP 工具名及邮件幂等键前缀。源码更新不会自动改写已经运行的服务、数据库、向量索引或浏览器本地数据。

- 先停止新增业务请求并备份数据库、工作簿和工具账本；已生成的邮件幂等键和冻结正文不要批量改写。待处理邮件应先在原部署中完成或经人工核对后停止，避免旧任务被当作新任务重新发送。
- 保留原 Compose 项目名或显式绑定已有命名卷，避免新目录下启动时误建空卷。已有 MySQL 数据库与账号不会因修改容器初始化环境变量而自动改名，可通过本地 Compose 覆盖文件显式指定实际的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，或安排独立的数据迁移。
- H2 与 Excel 路径应在服务停止后迁移或通过 `DB_URL`、`MCP_EXCEL_LOCAL_PATH`、`TOOLS_EXCEL_PATH` 指向现有文件；不要让新旧应用同时打开同一 H2 文件或写入同一工作簿。工具账本使用原有文件，不能删除后重放旧邮件。
- 已有 Chroma 数据可通过 collection 配置继续使用，或从业务数据库重建；Redis 作为缓存可在切换后从持久化记录恢复。浏览器缓存 key 已更新，切换前应结束正在生成的会话并保存必要的请求 ID。
- 主应用、独立 MCP 服务、前端和 API 调用方需一起更新工具名、配置变量及请求头。自行准备的模型也需以当前 `Modelfile`、GGUF 文件名和模型标签重新导入。

**已有本机实例的更新方式：**保留 `mindbridge` Compose 项目、原 MySQL 数据库和 Chroma collection，通过私有部署覆盖文件更新主应用与 MCP 镜像；容器仍叫 `mindbridge-*` 不影响运行新版 MindHarbor。不要直接套用面向空环境的启动命令创建另一套数据库。私有覆盖文件、数据库备份与回滚配置应放在 Git 忽略的 `data/deploy/` 中，可能含运行凭据，不能上传。

升级标签与知识包功能会新增 `reviewed_knowledge_packs` 表，无需重新导入全部向量。主应用就绪后检查 `/actuator/health` 和管理员的知识包列表；应能看到 8 个可审核知识包，未批准的包保持草稿。普通标签缓存可直接使用；免评审路径须在管理员审核后才生效。

### 运行与访问边界

- 演示账号、HTTP Basic、默认数据库密码及基础 Compose 的端口映射用于开发。对外提供服务前，需要替换演示账号机制、配置 HTTPS、限制数据库和管理入口，并检查内置 MCP 服务端的暴露与鉴权。
- 已有 `ddl-auto=update` 和启动期迁移逻辑，不能替代经过审查的生产数据库迁移、备份与恢复流程。Excel 当前按单实例写入维护，不应让多个独立写入者共享工作簿。
- SSE 经反向代理时需核对缓冲和超时设置；邮件里的应用链接、回调入口与学生聊天页面是不同配置，不要将临时回调地址当作公共应用地址。
- `model not found`：用 `ollama list` 核对实际标签，并显式设置 `OLLAMA_MODEL`。克隆源码不包含模型权重。
- `Connection refused`：分别检查 Ollama、Redis、数据库的监听地址和端口；容器内的 `localhost` 指向容器自身。
- `401 / 403`：检查学生/管理员账号和接口权限；管理员不能发起学生聊天。
- 启用了 Chroma 但没有向量命中：检查 embedding 是否成功、collection 是否匹配，以及知识是否已发布或需要重建索引。
- `Unsupported tag !override`：更新到支持该标签的 Compose，或先使用本机 Maven 的文本聊天路径。
- 端口占用：本机启动可设置 `SERVER_PORT`；Docker 的宿主机端口还需修改 Compose 映射。

运行数据、密钥和本机文件继续由 `.gitignore` 排除；不要使用强制添加把 `.env.resend.local`、数据库、报告或日志提交到公开仓库。

## 专题文档

- [多 Agent 协作](docs/multi-agent-collaboration-copy.md)
- [RAG 检索与重排](docs/rag-technical-guide.md)
- [标签候选缓存](docs/knowledge-tag-cache.md)
- [已审核知识包与风险回退](docs/reviewed-knowledge-packs.md)
- [流式回复与断线恢复](docs/reply-recovery.md)
- [Agent 检查点与步骤恢复](docs/agent-checkpoint-recovery.md)
- [长期记忆的事实变化与有效期](docs/memory-facts.md)
- [后台记忆维护](docs/background-memory.md)
- [模型调用观测](docs/model-observability.md)
- [回答长度与截断恢复](docs/answer-completion.md)
- [邮件投递、提醒与确认](docs/mail-workflow.md)
- [独立 MCP 工具服务](docs/mcp-tools-integration.md)
- [图片与扫描文档知识库](docs/image-knowledge-ingestion.md)
- [LoRA 微调与模型转换](docs/qwen25-7b-lora-finetune-guide.md)
- [RAGAS 工具](eval/README.md)
