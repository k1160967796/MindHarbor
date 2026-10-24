# 长期记忆的事实变化管理

## 实现结构

继续使用持久化 PROFILE 后台任务。前台仍由 MemoryAgent 读取画像和近期上下文，不新增同步模型校验调用。

1. ChatService 在准备结果事务中创建任务，记录来源用户消息 ID、本轮请求顺序以及请求记录创建时间（assertedAt）。
2. 后台抽取器一次返回属性 key、value、scope、主体、时间类型、原文证据、置信度和可选绝对日期。已有记忆的 key、背景、值、状态和时间会一起提供给模型。
3. MemoryFactPolicy 检查原文证据、主体、假设/时间/否定表达，规范常见 key 和值。不明确的信息保守处理。睡眠、压力、回答长短和语言的明确表达另有窄范围规则兜底：仍使用同一校验器，不增加模型调用；同属性规则候选优先于模型候选。
4. MemoryFactStore 在原有租约保护的事务里更新当前事实、写变更记录，创建 INDEX 任务。
5. INDEX 任务同步 Chroma；召回最终回查 MySQL 的用户、来源版本、有效状态、有效期和背景。

模型超时或 JSON 不合法时，若存在通过原文校验的明确规则候选，只提交这些候选，任务注明 RULE_FALLBACK 及错误类型，管理员界面可见。没有可靠规则候选则沿用失败重试。兜底不补造其余未提取事实；原始用户消息仍保留。单次模型输出上限为 1000 token，后台超时预算仍为 15 秒。

## 数据模型

`user_memory_items` 每名用户、每个 factKey、每个 factScope 保留一条当前记录，数据库唯一约束兜底。字段新增：

| 字段 | 含义 |
| --- | --- |
| factKey / factValue | 统一属性与值，如 sleep_status / improved |
| factScope | GLOBAL、DAILY、EXAM、FAMILY、WORK、RELATIONSHIP、OTHER |
| factStatus | ACTIVE、HISTORICAL、NEEDS_CONFIRMATION、EXPIRED、ARCHIVED |
| sourceMessageId / sourceOrder | 用户原文来源、请求进入系统的顺序 |
| assertedAt / effectiveAt | 用户陈述时间、能够明确验证的事实发生时间 |
| expiresAt / revision | 有效期、当前记录版本 |

只在原文明确包含 YYYY-MM-DD 时接受绝对事实日期；相对日期不会被模型猜测成具体日期。历史描述仍会标记 HISTORICAL。缺少时间不等于现在已经证实。

`user_memory_changes` 保存实际变化的前后数据、来源、原因和版本关系。REPLACED 事件将旧版本标记为 SUPERSEDED，但不为旧版本再插入一条当前事实。重复确认仅更新证据、时间、置信度和版本，不重复记录变化。

历史补充或不确定的新信息不会覆盖已有 ACTIVE 记录，会写入 HISTORICAL_OBSERVATION / REVIEW_REQUIRED 事件。它们可供本人查看，不自动作为当前事实召回。风险判断仍由现有 RiskGuardian 和报告链路处理，画像过期不表示风险解除。

用户删除画像时同时删除对应变更内容，异步清理向量；`memory_fact_deletions` 只保存属性、背景和删除时间，防止删除前已排队的抽取任务重新创建该事实。之后新的明确陈述仍可形成新记忆。原始聊天记录遵循原有聊天数据管理逻辑。

## 更新规则

- 统一 response_length、language、tone，以及 sleep_status、stress_status、exam_status、work_status、relationship_status、support_need、major、academic_stage。
- sleep_problem、sleep_quality、insomnia 等常见别名归入 sleep_status；不认识的属性保留为待确认，不强行作为当前事实。
- 背景不同分别保存，例如 EXAM 下睡眠困难与 DAILY 下睡眠良好可以共存。第一版使用上述受控背景，不区分每一次考试的独立事件编号。
- 同一属性和背景的新明确陈述更新旧值；更早 sourceOrder 不能覆盖该条新值，但旧任务里的独有其他属性允许补录。
- “如果……”“我朋友……”不形成当前用户事实；“上个月……”按历史处理；复杂否定、不确定信息不直接覆盖当前值。这些规则是保守补充，不是完备的中文语义理解器。
- 同一轮、同一属性和背景同时有过去与当前描述时，优先当前描述；同时出现两个不同的当前值则待确认，不依赖数组先后顺序任意选一个。value 禁止混入分号、冒号分隔的多个属性。
- 置信度使用本次结果，不再对新事实沿用旧事实的最高分。达到 0.55 才保留候选，低于 0.8 的模型候选待确认；明确规则的可信分固定为 0.9。两者都是内部取舍分数，不是校准后的概率。

## 有效期与召回

- 回答偏好、语言、语气、专业和学历阶段默认不设置自动过期。
- WELLBEING_PATTERN 默认 14 天，其他非稳定背景默认 90 天。通过 MEMORY_STATE_VALID_DAYS、MEMORY_CONTEXT_VALID_DAYS 配置。这是检索时效策略，非医学结论。
- ACTIVE 超过 expiresAt 后立即在 API 和召回层视作 EXPIRED；无需等定时任务修改数据库状态字段。过期内容保留，后续明确确认可重新激活。
- 最多保留 40 条活跃画像，稳定属性优先；超出的非稳定条目归档，不直接丢弃变更依据。历史及归档目前不自动按年限删除。
- 召回先从 MySQL 读取至多 40 条有效候选。向量候选默认扩大至 24 条（topK=6 的四倍，最多 32），过滤旧版本、其他用户和失效记录后取前 6，再用当前有效的 SQL 候选补足，最终最多 8 条。
- Chroma 写入新版本后仅删除更早 sourceOrder 的索引，防止迟到的旧工作者删除新版本；失效条目的索引任务只删除不晚于自身版本的记录。若索引任务尚未运行，MySQL 过滤仍阻止失效内容生效。
- 本轮涉及睡眠、压力、回答长短、语言、考试等已知属性时，前台保守屏蔽对应旧画像，让本轮原文参与决策，减少异步更新滞后影响。其他属性仍依靠来源标注和“近期原文优先”的提示约束，不能声称已解决所有语义冲突。
- MemoryBrief 标注当前有效、背景、自述时间、事实时间；不将 HISTORY/待确认/过期内容平铺成当前事实。

## 升级与界面

新增字段均允许旧行为空，保留原记录。旧画像显示“待确认”，不会无依据地补写背景、事实时间或历史版本。之后的明确偏好可以重新确认同一旧记录；其他旧画像可由用户查看并删除。

学生的“画像记忆”列表显示状态、背景、确认时间及有效期，每项可展开“查看事实变化”。`GET /api/profile/memory/{id}/changes` 只允许所属用户读取，最多返回最近 50 次；不能利用别人的记忆 ID 查询其证据。

数据库变更包含 user_memory_items、memory_tasks 新字段，以及 user_memory_changes 和 memory_fact_deletions 两张新表。本地使用现有 ddl-auto=update 升级，部署前备份数据库与旧镜像。不要回退后把新后台任务交给旧抽取逻辑处理。

## 自动化验证

MemoryFactAcceptanceTests 覆盖偏好更新、重复确认、睡眠别名与恢复、背景共存、历史回顾、假设和他人、否定、置信度、旧任务补录、有效期、旧向量补召回、本轮原文屏蔽、越权/删除、旧数据与事实时间。MemoryFactPolicyTests 补充复杂否定及真实 HTTP 请求格式的 Chroma 索引清理测试。

集成测试中的模型输出和向量结果是可控夹具，用于验证代码边界；不代表真实小模型对所有表达都能准确抽取。

## 本地验收记录（2026-10-23）

- 139 项自动化测试通过，0 失败、0 错误；其中本次新增 18 项集成测试、11 项规则及 HTTP 索引测试。
- 真实本地 Ollama（qwen2.5:1.5b）＋MySQL＋Chroma 完成三轮独立测试账号对话：

| 输入 | 当前事实 | 结果 |
| --- | --- | --- |
| 最近临近期末考试，晚上总是睡不着 | sleep_status / EXAM / difficulty | ACTIVE，画像和索引任务 SUCCESS |
| 考试已经结束，最近睡眠恢复 | 同一条记录改为 improved | ACTIVE，保存 REPLACED 变化及旧证据 |
| 上个月准备考试时曾睡不着 | 仍为 improved | 仅新增 HISTORICAL_OBSERVATION，不覆盖当前状态 |

三轮 requestId 分别为 `b1e45c67-3ef4-4d0d-a349-6f264be62e25`、`11b3eaa5-ddf8-48a4-8a5b-3e1ce3b810f5`、`56b01d9e-04e3-458c-bb78-4f0bc4333996`。均为模拟输入，三轮最终回答 SUCCESS，抽取路径均为 MODEL_WITH_RULES。SQL 验证睡眠记录一直为 ID=7，最终 improved / ACTIVE / revision=3。

首次联调确实出现过小模型错配属性及 JSON 不合法，已据此补充明确表达兜底和可见结果标记；保留前后验证记录，不把规则完成的识别表述为模型独立准确完成。JSON 失败时的 RULE_FALLBACK 已有故障注入集成测试覆盖，最终三轮未触发该异常分支。

测试账号已停用；未修改学生演示账号的画像。旧画像保持可查看的待确认状态。数据库和旧镜像备份保留于忽略目录 `data/deploy/20261023-memory-facts/` 和镜像标签 `mindharbor-agent:before-memory-facts`。本次只更新本机 Docker，未推送 GitHub。
