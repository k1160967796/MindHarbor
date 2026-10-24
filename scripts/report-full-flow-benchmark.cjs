const fs=require('node:fs'),cp=require('node:child_process');
const dir='data/benchmarks/full-flow-20261023';
const manifest=JSON.parse(fs.readFileSync(dir+'/manifest.json','utf8')),db=manifest.db;
const raw=fs.existsSync(dir+'/results.jsonl')?fs.readFileSync(dir+'/results.jsonl','utf8').trim():'';
const results=raw?raw.split('\n').map(JSON.parse):[];
const stopState=fs.existsSync(dir+'/stopped.json')?JSON.parse(fs.readFileSync(dir+'/stopped.json','utf8')):null;
const stats=a=>{a=a.filter(Number.isFinite).sort((a,b)=>a-b);return {n:a.length,avg:a.length?a.reduce((s,x)=>s+x,0)/a.length:0,p50:a[Math.max(0,Math.ceil(a.length*.5)-1)]||0,p95:a[Math.max(0,Math.ceil(a.length*.95)-1)]||0,max:a.at(-1)||0};};
const count=(a,fn)=>a.reduce((m,r)=>{const k=fn(r)||'UNKNOWN';m[k]=(m[k]||0)+1;return m;},{});
function sql(s){const p=cp.spawnSync('docker',['exec','-i','mindbridge-mysql','sh','-c',`exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ${db} --batch --raw --skip-column-names --default-character-set=utf8mb4`],{input:s,encoding:'utf8',maxBuffer:64*1024*1024});if(p.status)throw Error(p.stderr);return p.stdout.trim();}
function exportRows(table,excluded=[]){const fields=sql(`SHOW COLUMNS FROM ${table}`).split('\n').map(x=>x.split('\t')[0]).filter(f=>!excluded.includes(f));return sql(`SELECT JSON_OBJECT(${fields.map(f=>`'${f}',\`${f}\``).join(',')}) FROM ${table}`).split('\n').filter(Boolean).map(JSON.parse);}
const tables={};for(const table of ['memory_tasks','agent_execution_steps','model_call_records','report_tasks','psychological_reports','user_memory_items','user_memory_changes','conversation_memory_summaries'])tables[table]=exportRows(table,table==='report_tasks'?['mail_payload']:[]);
fs.writeFileSync(dir+'/database-evidence.json',JSON.stringify(tables,null,2));
// Raw evidence includes an in-flight request at stop time. Aggregate only fully recorded samples.
const completedIds=new Set(results.map(r=>r.requestId));
const completedToolIds=new Set(results.flatMap(r=>(r.tools||[]).map(t=>t['t.id'])));
const completedTools=tables.report_tasks.filter(t=>completedToolIds.has(t.id));
const completedReportIds=new Set(completedTools.map(t=>t.report_id));
tables.memory_tasks=tables.memory_tasks.filter(t=>completedIds.has(t.request_id));
tables.report_tasks=completedTools;
tables.psychological_reports=tables.psychological_reports.filter(t=>completedReportIds.has(t.id));
const steps=results.flatMap(r=>r.steps||[]),calls=results.flatMap(r=>r.calls||[]);
const grouped=(items,key,val)=>Object.fromEntries([...new Set(items.map(key))].map(k=>[k,stats(items.filter(r=>key(r)===k).map(val))]));
const stages=grouped(steps,r=>r.agent,r=>r.duration_ms);
const modelStages=grouped(calls,r=>r.phase,r=>r.durationMs);
const rag=steps.filter(s=>s.agent==='KNOWLEDGE_AGENT');
const ragTiming={};for(const field of ['rewriteMs','cacheLookupMs','recallMs','reviewMs','elapsedMs'])ragTiming[field]=stats(rag.map(s=>Number(s.observation.match(new RegExp('(?:^|; )'+field+'=(\\d+)'))?.[1])).filter(Number.isFinite));
const failures=results.filter(r=>r.reply?.status!=='SUCCESS'||r.tasks?.some(t=>t.status==='FAILED')||r.backgroundTimeout||r.error);
const report={generatedAt:new Date(),status:stopState?'STOPPED_BY_USER':results.length===1000?'COMPLETE':'RUNNING',stopState,complete:results.length===1000,completed:results.length,total:1000,replyStatus:count(results,r=>r.reply?.status),
 fullFlowSuccess:results.filter(r=>r.reply?.status==='SUCCESS'&&r.checkpoints?.[0]?.status==='COMMITTED'&&r.tasks?.length&&r.tasks.every(t=>['SUCCESS','SKIPPED'].includes(t.status))&&r.tools?.every(t=>['SUCCESS','CANCELLED'].includes(t['t.status']))&&!r.backgroundTimeout).length,
 firstToken:stats(results.map(r=>r.firstTokenMs)),reply:stats(results.map(r=>r.replyMs)),allWork:stats(results.map(r=>r.allWorkMs)),stages,modelStages,ragTiming,
 riskReview:results.filter(r=>r.label!=='高风险'&&r.checkpoints?.[0]?.risk==='HIGH').map(r=>({index:r.index,label:r.label,requestId:r.requestId})),
 routingReview:results.filter(r=>r.label!=='正常'&&r.checkpoints?.[0]?.intent==='CHAT').map(r=>({index:r.index,label:r.label,requestId:r.requestId})),
 routes:count(results,r=>r.checkpoints?.[0]?.intent),risks:count(results,r=>r.checkpoints?.[0]?.risk),
 labels:count(results,r=>r.label),splits:count(results,r=>r.split),
 memoryTaskStatus:count(tables.memory_tasks,r=>r.kind+':'+r.status),memoryNotes:count(tables.memory_tasks.filter(t=>t.kind==='PROFILE'),r=>r.result_note),
 mailStatus:count(tables.report_tasks.filter(r=>r.kind==='MAIL'),r=>r.provider+':'+r.status+':'+r.delivery_status),
 excelStatus:count(tables.report_tasks.filter(r=>r.kind==='EXCEL'),r=>r.status),
 factStatus:count(tables.user_memory_items,r=>r.fact_status),factChanges:count(tables.user_memory_changes,r=>r.reason),summaryCount:tables.conversation_memory_summaries.length,
 checkpointRows:results.filter(r=>r.checkpoints?.length===1).length,
 packHits:rag.filter(r=>r.observation.includes('review=SKIPPED_APPROVED_PACK')).length,
 modelOutcomes:count(calls,r=>r.phase+':'+r.outcome),
 tokens:{known:calls.reduce((a,r)=>a+(r.totalTokens||0),0),input:calls.reduce((a,r)=>a+(r.inputTokens||0),0),output:calls.reduce((a,r)=>a+(r.outputTokens||0),0),missingCalls:calls.filter(r=>r.totalTokens==null).length,totalCalls:calls.length},
 failures:failures.map(r=>({index:r.index,requestId:r.requestId,reply:r.reply?.status,error:r.error,backgroundTimeout:r.backgroundTimeout,failedTasks:r.tasks?.filter(t=>t.status==='FAILED')}))};
fs.writeFileSync(dir+'/summary.json',JSON.stringify(report,null,2));
const table=o=>Object.entries(o).map(([k,v])=>`| ${k} | ${v.n} | ${(v.avg/1000).toFixed(3)} | ${(v.p50/1000).toFixed(3)} | ${(v.p95/1000).toFixed(3)} |`).join('\n');
const heading='| 环节 | 实际执行次数 | 平均秒 | P50 秒 | P95 秒 |\n|---|---:|---:|---:|---:|\n';
const md=`# MindBridge 1,000 条全流程测试\n\n更新时间：${report.generatedAt.toISOString()}\n\n**${stopState?'已按用户要求停止；完整记录 '+report.completed+' / 1,000 条，另有 1 条在途请求不计入耗时统计':report.complete?'已完成 1,000 条':'测试进行中，仅完成 '+report.completed+' / 1,000 条，以下不是最终结论'}**\n\n## 范围与方法\n\n- 固定分层抽样：正常、焦虑、低落、高风险各 250 条；240 条来自原测试集，760 条来自训练集。用于工程运行验证，不能作为独立泛化准确率。\n- 101 个隔离账号：先导 2 组各 5 轮，主批次 99 组各 10 轮同类数据集输入；每轮等待后台任务终态再继续，避免后台被持续流量饿死。\n- 当前真实 Ollama qwen2.5:1.5b / bge-m3；真实 HTTP/SSE、MySQL、Redis、Chroma、后台任务及 MCP Excel；只将邮件改为 LOG，不发送真实邮件，也不宣称验证了外部投递。\n- 使用独立测试数据库、缓存、向量库、Excel。模型仍使用同一台本地机器，因此耗时不是高并发容量测试。\n- CHAT 正常跳过检索和报告；低风险不发高风险预警。跳过按路由记录，不强行让所有输入触发所有分支。\n- 这 1,000 条是聊天请求，不包括文档上传/OCR；正常运行不能单独证明进程崩溃恢复、旧任务覆盖等故障边界。\n\n## 完成情况\n\n- 回复状态：${JSON.stringify(report.replyStatus)}\n- 回复、检查点、后台任务及相关工具全部达到成功/合法跳过：${report.fullFlowSuccess}/${report.completed}\n- 单请求恰好一条检查点：${report.checkpointRows}/${report.completed}\n- 后台任务：${JSON.stringify(report.memoryTaskStatus)}\n- 画像抽取来源/兜底：${JSON.stringify(report.memoryNotes)}\n- Excel：${JSON.stringify(report.excelStatus)}\n- 邮件（仅日志模式）：${JSON.stringify(report.mailStatus)}\n- 已生成会话摘要：${report.summaryCount}\n- 审批知识包直接命中次数：${report.packHits}\n\n## 路由复核\n\n非正常标签但被路由为 CHAT 的待人工复核样本：${JSON.stringify(report.routingReview)}。非高风险标签但评为 HIGH 的待复核样本：${JSON.stringify(report.riskReview)}。技术执行成功不代表路由或回答质量正确；合成标签也不等于临床结论。\n\n## 用户可见耗时\n\n${heading}${table({首字等待:report.firstToken,完整回复:report.reply,含后台任务完成:report.allWork})}\n\n完整回复包含准备阶段与流式生成；后台失败也计入其实际重试耗时。首字无返回的请求不进入首字均值。\n\n## Agent 环节\n\n${heading}${table(report.stages)}\n\n按该 Agent 实际执行次数统计；重入会再次计数。阶段包含该 Agent 内的模型/数据库/检索操作，不应再与模型耗时相加。\n\n## 模型调用\n\n${heading}${table(report.modelStages)}\n\n包含成功、超时和失败调用；每次重试独立计数。模型耗时与 Agent 耗时存在包含关系。\n\n## 知识检索子步骤\n\n${heading}${table(report.ragTiming)}\n\n来自实际运行轨迹。recallMs 包含缓存查询；无该字段的知识包直达路径不按零混入均值。现有轨迹未独立计时 BM25 和 Chroma 网络耗时，因此不虚构其平均值。\n\n## Token 与异常\n\n- 已报告 Token：${report.tokens.known}（输入 ${report.tokens.input}，输出 ${report.tokens.output}）。\n- 缺失用量的调用：${report.tokens.missingCalls}/${report.tokens.totalCalls}；缺失不按零宣称真实成本。\n- 模型结局：${JSON.stringify(report.modelOutcomes)}\n- 有回复/后台/执行异常的样本：${report.failures.length}。逐条证据在 summary.json / results.jsonl。\n\n## 原始证据\n\n同目录 samples.json、manifest.json、results.jsonl、database-evidence.json、summary.json；真实 Excel 位于 ${manifest.excelPath}。\n`;
fs.writeFileSync(dir+'/report.md',md);
console.log(JSON.stringify({completed:report.completed,replyStatus:report.replyStatus,fullFlowSuccess:report.fullFlowSuccess,firstTokenAvgMs:report.firstToken.avg,allWorkAvgMs:report.allWork.avg,memoryTaskStatus:report.memoryTaskStatus}));
