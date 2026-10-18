# 图片、扫描 PDF、表格与公式入库

## 实现流程

管理员上传 PDF / PNG / JPEG → PDFBox 按页渲染 → PP-StructureV3 识别版面、文字、表格、公式 → 图片/图表区域调用 Ollama 视觉模型 → 保存待校对片段与原图 → 管理员修订并发布 → 文本 Embedding / Chroma / BM25 检索。

识图只发生在资料上传时。聊天时复用已经发布的文字知识，不会再次识别图片。默认关闭图片解析，原有文本 PDF、Markdown、TXT 上传仍可使用。启用后，PDF 所有页面都走版面识别，以免遗漏有文字层的混合图文页。

## 启动（推荐 Docker 运行 OCR，Ollama 在宿主机）

需要 Docker Desktop、Ollama，以及能运行视觉模型的内存/显存。模型磁盘大小不等于实际推理内存要求。

```powershell
# 单独拉取视觉模型，原有对话模型不变。
ollama pull qwen2.5vl:7b

# 仅运行 OCR 服务，适合 Java 应用在 IDE 中启动。
docker compose -f docker-compose.yml -f docker-compose.media.yml up -d --build document-parser

# 在同一个终端启动 Java，或把以下值填到 IDE 的运行环境变量。
$env:KNOWLEDGE_MEDIA_ENABLED = 'true'
$env:KNOWLEDGE_OCR_BASE_URL = 'http://localhost:8091'
$env:KNOWLEDGE_VISION_BASE_URL = 'http://localhost:11434'
$env:KNOWLEDGE_VISION_MODEL = 'qwen2.5vl:7b'
$env:KNOWLEDGE_MEDIA_TIMEOUT_SECONDS = '300'
# Maven 需要 Java 17 或以上。
mvn spring-boot:run
```

如果整套应用都使用 Docker：

```powershell
docker compose -f docker-compose.yml -f docker-compose.media.yml up -d --build
```

首次 OCR 请求会下载/加载模型，可能超过请求超时。查看 OCR 容器日志确认下载完成后重试。模型缓存挂载在 `document-models` 卷。`/health` 的 `modelsLoaded` 仅表示本进程是否已完成模型初始化，不是识别质量检测。

OCR 使用独立 Python 服务和一个工作进程，默认 CPU 推理。需要 GPU 时应另行安装与 CUDA 匹配的 PaddlePaddle GPU 包并配置容器设备；仅修改 `PADDLE_DEVICE` 不能把 CPU 镜像变成 GPU 镜像。

若独立部署 OCR 到其他机器，同时设置服务端 `DOCUMENT_PARSER_TOKEN` 与 Java 端 `KNOWLEDGE_OCR_TOKEN`，在受信网络或 TLS 代理后提供服务。默认 Compose 端口只绑定本机。

## 管理员操作

1. 登录管理员后台，上传文件。
2. 图片解析可能耗时数分钟；上传请求等待解析完成，单实例同一时刻只允许一个文件解析。它在独立工作线程执行，不占用 WebFlux 事件线程。这不是持久化任务队列，进程重启后需重新上传。
3. 在“知识内容校对”里查看页码、内容类型、识别结果及原图。
4. 核对数字、条件、箭头方向、公式及表格行列关系，修改文本后点击“校对完成，发布”。表格 HTML 可以保留或修改为 Markdown；编辑器按纯文本展示，不能执行其中的 HTML。
5. 只有已发布片段进入检索。发布会生成文本向量（若已配置 Embedding）并镜像到已配置的 Chroma；无 Embedding 时仍可通过 BM25 检索。

图片文字、表格、公式、图表说明全部待校对，避免把不确定的机器识别当作已确认资料。视觉模型失败时保留 OCR 和原图，给出明确占位提示，管理员必须补全内容后发布。OCR 服务不可用时整次导入失败，旧来源不会被删除。

同名文件成功上传后会替换旧片段；如果新版本全是待校对内容，该来源在发布前暂时不可检索。空文件或解析失败不会清除旧知识。原图使用 SHA-256 文件名保存在 `data/knowledge-images`；未引用的旧图片暂不自动清理，以免误删复核资料，可离线核对数据库引用后清理。

## 配置

| 环境变量 | 默认值 | 含义 |
|---|---|---|
| KNOWLEDGE_MEDIA_ENABLED | false | 启用图片及 PDF 版面解析 |
| KNOWLEDGE_OCR_BASE_URL | http://localhost:8091 | OCR 服务 |
| KNOWLEDGE_OCR_TOKEN | 空 | 可选 Bearer token |
| KNOWLEDGE_VISION_ENABLED | true | 解析图表、插图；关闭时需人工补充图像说明 |
| KNOWLEDGE_VISION_BASE_URL | http://localhost:11434 | 独立的 Ollama 视觉服务 |
| KNOWLEDGE_VISION_MODEL | qwen2.5vl:7b | 必须支持图片输入 |
| KNOWLEDGE_MEDIA_TIMEOUT_SECONDS | 120 | 单次 OCR 或视觉调用超时，Compose 覆盖为 300 |
| KNOWLEDGE_RENDER_DPI | 200 | PDF 渲染分辨率，限定 72–300 |
| KNOWLEDGE_MAX_PDF_PAGES | 30 | 单份 PDF 页数限制 |
| KNOWLEDGE_MAX_IMAGE_PIXELS | 20000000 | 单张图片/页面像素上限 |
| KNOWLEDGE_IMAGE_PATH | ./data/knowledge-images | 原图存储目录 |

文件上限仍为 10MB。普通文本按原分块规则切分；表格、公式和图像说明整块保留，超过 12000 字符拒绝导入，提示拆分。Markdown 中的远程图片链接不会自动下载；需要将图片作为文件上传。

## 接口与存储

- `POST /api/admin/knowledge/file`：multipart 文件上传，返回来源和保存的片段数（包含待校对片段）。
- `GET /api/admin/knowledge/review`：待校对片段。
- `PUT /api/admin/knowledge/review/{id}`：`{"content":"校对后的文字"}`，发布片段。
- `GET /api/admin/knowledge/images/{reference}`：原图，管理员权限，不公开文件系统路径。

`knowledge_chunks` 新增 `page_number`、`block_type`、`image_ref`、`needs_review`。当前开发配置使用 Hibernate `ddl-auto=update` 添加列，原有记录默认已发布；生产使用独立迁移管理时应先添加这些列并把历史记录 `needs_review` 初始化为 false。

Chroma 镜像携带页码、类型和图片引用。数据库是权威来源：即使 Chroma 中残留旧 ID 或未审核内容，查询也会过滤，邻居扩展同样排除待校对片段。Chroma 接入使用 HTTP v2、服务端返回的集合 UUID，以及同一个 Embedding 模型生成的入库/查询向量；数据库与外部向量库仍不属于一个原子事务。

## 这台 Windows 电脑的启动方式

在项目目录执行 `./scripts/start-local.ps1`；已构建且代码未变化时使用 `./scripts/start-local.ps1 -NoBuild`。脚本启动 Docker 服务并打开 http://localhost:8080。

学生账号 `student / student123`，管理员账号 `admin / admin123`。管理员登录后可使用“知识库上传”和“知识内容校对”。本地测试邮件在 http://localhost:8025 查看，不会投递到外部邮箱。

`docker-compose.local.yml` 使用轻量聊天模型 `qwen2.5:1.5b`、视觉模型 `qwen2.5vl:3b` 和本地 Embedding `bge-m3`。这些是基础模型，并非简历中提到的微调权重。MySQL 映射到本机 3307，避开已有 3306 服务。首次启动重建已有已发布知识的向量索引，使用带 `bge_m3` 后缀的新集合，保留旧集合；`MINDBRIDGE_KNOWLEDGE_REINDEX_ON_START=false` 可关闭后续启动重建。更换 Embedding 模型时必须同时重建数据库向量和新的 Chroma 集合。

OCR 保留文字、表格、公式识别，图表交给 Ollama 视觉模型，避免同时加载另一套大型图表模型。首次加载和扫描 PDF 解析会较慢。

容器构建时通过 `services/document-parser/patch_paddlex.py` 修复 PaddleX 3.3 在关闭图表识别后仍加载 Chart2Table 的问题；补丁会检查目标源码，遇到上游实现变化时明确中止构建。升级 PaddleX 时需复核此补丁。

## 验证

```powershell
mvn test
# Python API 合约测试无需 Paddle 模型；测试依赖 fastapi/python-multipart/pillow/httpx。
cd services/document-parser
python -m unittest -v
```

Java 测试覆盖原生 PDF 页码、扫描页渲染、真实 HTTP OCR/视觉请求格式、视觉失败、文件限制、权限、待校对内容不可检索、校对发布和旧文本回归。Python 测试覆盖版面输出转换、API 合约、鉴权、忙碌拒绝及坏图片。测试中的 OCR/视觉推理结果是替身，不代表真实模型识别质量。

部署后用一页扫描文字、一张表格、一张流程图和一条公式做真实验收：确认原图对应、阅读顺序和数字准确，确认校对前无法召回、发布后能够召回。OCR 和视觉推理仍可能出错，模型输出不构成正确性证明。

实现依据：
- https://github.com/PaddlePaddle/PaddleOCR/blob/release/3.3/docs/version3.x/pipeline_usage/PP-StructureV3.md
- https://docs.ollama.com/capabilities/vision
- https://pdfbox.apache.org/3.0/faq.html
