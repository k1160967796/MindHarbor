# MindHarbor（心港）

面向校园心理陪伴场景的 Java 智能体项目，使用 Spring Boot、Spring AI 和原生 HTML/CSS/JavaScript，提供多 Agent 路由、RAG、SSE 流式对话、用户画像记忆和后台预警任务。

项目用于工程演示与研究，不替代专业心理咨询、诊断或紧急救援。本文描述的是仓库当前实现，不代表已部署的公共服务或经临床验证的效果。

## 已实现的能力

| 能力 | 当前实现 |
| --- | --- |
| 意图路由 | 区分 `CHAT / CONSULT / RISK`；普通学习、编程和校园事务不必进入心理咨询链路 |
| 多 Agent | 记忆读取、主控路由、知识检索、风险守护、陪伴或咨询回复规划；Agent loop 最多 8 步 |
| 流式回复 | SSE 输出；可重试的生成失败最多 3 次总尝试；支持按请求 ID 查询、恢复和重新生成 |
| 检索增强 | BM25、可选向量召回、query 改写、候选评审与重排；资源不可用时保留检索降级路径 |
| 对话与画像记忆 | H2/MySQL 保存持久记录，Redis 缓存近期对话，Chroma 可用于画像语义召回 |
| 风险报告与工具任务 | 咨询/风险分支按规则生成后台报告；Excel 与高风险邮件是独立持久化任务，学生回复不等待工具执行 |
| 邮件跟进 | 支持日志、SMTP 以及独立 MCP/Resend 模式；区分发送受理、投递和辅导员主动确认 |
| 知识库管理 | 管理员录入文本、上传文件；可选 OCR/视觉解析与图片内容校对、发布 |
| 管理后台与评测 | 对话记录、报告、工具状态和 Agent 执行轨迹；Java 评测导出及可选 RAGAS 工具 |

项目展示名称为 **MindHarbor**。为兼容已有数据和部署，代码中的 `com.mindbridge` 包名、`mindbridge-*` 构建产物、模型标签及 `MINDBRIDGE_*` 配置名仍沿用原有标识。

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

此模式使用 `data/mindbridge.mv.db` 保存 H2 数据，Redis 可用时缓存近期对话；没有 embedding 配置时，知识检索使用本地 BM25。默认邮件模式为 `log`，不发送真实邮件。环境变量示例按新终端、未额外配置其他服务的情况编写；已有环境变量会影响最终配置。

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
  -> MemoryAgent -> SupervisorAgent
                     -> KnowledgeAgent / RiskGuardianAgent（按路由执行）
                     -> CompanionAgent 或 CounselorAgent
  -> Spring AI -> Ollama 或 OpenAI 兼容接口 -> SSE

报告落库 -> 持久化任务
           ├─ Excel 归档
           └─ 高风险邮件 -> 投递事件核对 -> 辅导员主动确认
```

`AgentRuntimeService` 调度有限步 Agent loop；`ChatService` 组织对话与流式生成；`ReplyStore` 管理请求身份、执行权、重试和完整回复保存。后台报告及轨迹包含评估信息，学生回复不展示风险等级或后台报告字段。

Excel 与邮件分别领取、执行和更新状态，**邮件不等待 Excel 成功**。数据库、Redis、工作簿和邮件服务并非同一个事务；持久化任务、重试和结果核对也不等于跨系统原子提交或严格只发送一次。

```text
src/main/java/com/mindbridge/agent/
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

首次运行可使用上文的公共模型，也可将 `OLLAMA_MODEL` 设置为自己已安装的模型标签。仓库默认值仍是 `mindbridge-qwen2.5-7b-ft:latest`，它**不是克隆仓库后自动获得的模型**。

GitHub 源码不包含 GGUF、LoRA adapter、训练数据集、数据库、业务报告、私有配置或 `.tools/`。若有自行准备的项目 GGUF，将其放至：

```text
models/mindbridge-qwen2.5-7b-ft/mindbridge-qwen2.5-7b-ft-q4_k_m.gguf
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
  ├─ mindbridge_write_excel_report
  ├─ mindbridge_send_risk_alert -> Resend
  └─ mindbridge_get_mail_status
```

公开仓库提供 [.env.example](.env.example) 作为字段说明。仅在准备接入真实邮件时，将其复制为 `.env.resend.local` 并在本机补齐配置；Spring Boot 本地 Maven 启动不会自动读取此文件。

独立工具模式至少需要 `RESEND_API_KEY`、`ALERT_MAIL_FROM`、`ALERT_MAIL_RECIPIENTS`、`ALERT_RECIPIENT_ACCOUNTS` 和强随机 `MCP_SERVICE_TOKEN`。接收投递回调还需 `RESEND_WEBHOOK_SECRET` 及服务商可达的回调地址；邮件链接通过 `MINDBRIDGE_PUBLIC_URL` 指向应用。`localhost` 只适合本机点击测试。

Windows 的 `scripts/start-mcp.ps1` 会在 Token 配置项不存在时生成令牌，开启 MCP 模式并处理已有持久化任务；不要在未检查配置和待处理任务时运行。完整接入步骤见 [独立 MCP 工具说明](docs/mcp-tools-integration.md) 与 [邮件跟进流程](docs/mail-workflow.md)。

Excel 和邮件独立执行；`SUCCESS / ACCEPTED`、`DELIVERED`、辅导员主动确认是不同状态，不能互相替代。工具端账本、幂等窗口和文件单写入者的限制见上述专题文档。

## 配置速查

以下是 `src/main/resources/application.yml` 的默认值；Compose 覆盖文件和显式环境变量可能改变它们。

| 环境变量 | 默认值 / 说明 |
| --- | --- |
| `AI_PROVIDER` | `ollama`；也支持 `openai` |
| `OLLAMA_BASE_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `mindbridge-qwen2.5-7b-ft:latest` |
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
| `RAG_EVAL_ENABLED` | `false` |

配置名保留原有技术标识，例如 `MINDBRIDGE_PUBLIC_URL`，不要改写为未被代码读取的 `MINDHARBOR_*` 变量。其他参数见配置文件和 `docs/`。

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
| `GET /api/admin/reports` | 管理员查看心理报告 |
| `GET /api/admin/run-traces` | 管理员查看 Agent 轨迹 |
| `POST /api/admin/knowledge` | JSON 录入知识，字段为 `source`、`content` |
| `POST /api/admin/knowledge/file` | multipart 文件上传，字段名为 `file` |
| `GET /api/admin/knowledge/review` | 待校对知识块 |
| `PUT /api/admin/knowledge/review/{id}` | 校对并发布知识块 |

SSE 包含 `meta`、`start`、`token`、`retry`、`heartbeat`、`done`、`error`、`busy` 等事件。**只有 `done` 或查询到 `SUCCESS` 才表示回复成功**，不能把连接关闭当成成功。

生成失败或中断不保存半段助手正文。自动重试复用已准备的提示词，恢复是重新生成整段回答，不是继续拼接旧 token；准备结果未落库时进程崩溃、生成预算耗尽等情况仍可能需要重新提交。完整边界见 [回复恢复说明](docs/reply-recovery.md)。

## 测试与评测

```bash
mvn test
mvn -f services/mcp-tools/pom.xml test
```

现有测试覆盖 Agent 路由、SSE、回复恢复、知识检索与校对、网络超时、邮件幂等参数和任务状态等；独立 MCP 集成测试使用本地模拟邮件服务。测试通过不等于真实邮件已投递或模型效果已验证。

RAG 评测分两步：先由 Java 执行检索与回答生成，导出报告，再由可选 Python 工具调用评审模型计算 RAGAS 指标。默认数据集有 100 条场景样本，不是临床或线上效果证明。

Bash 导出示例：

```bash
AI_PROVIDER=ollama \
OLLAMA_MODEL=qwen2.5:1.5b \
USE_CHROMA=false \
MEMORY_USE_CHROMA=false \
MCP_SERVER_ENABLED=false \
RAG_EVAL_ENABLED=true \
RAG_EVAL_EXIT_AFTER_RUN=true \
DB_URL='jdbc:h2:mem:rag-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
mvn spring-boot:run
```

报告默认保存到 `target/rag-eval-report.json`。完整生成会调用模型多次，耗时取决于服务性能；可通过 `RAG_EVAL_DATASET` 指向更小的数据集。Python 安装与评审模型选择见 [评测工具说明](eval/README.md)。不要在没有对应报告时声称已获得某个准确率或 RAGAS 分数。

## 部署边界与排障

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
- [流式回复与断线恢复](docs/reply-recovery.md)
- [邮件投递、提醒与确认](docs/mail-workflow.md)
- [独立 MCP 工具服务](docs/mcp-tools-integration.md)
- [图片与扫描文档知识库](docs/image-knowledge-ingestion.md)
- [LoRA 微调与模型转换](docs/qwen25-7b-lora-finetune-guide.md)
- [RAGAS 工具](eval/README.md)
