// Real HTTP/SSE benchmark, with real durable background jobs; no mocked models.
// Run only against the isolated deployment made by prepare-full-flow-benchmark.cjs.
const fs=require('node:fs'),cp=require('node:child_process'),crypto=require('node:crypto');
const dir='data/benchmarks/full-flow-20261023',base='http://localhost:18080';
const db=JSON.parse(fs.readFileSync(dir+'/manifest.json','utf8')).db;
const pause=ms=>new Promise(r=>setTimeout(r,ms)),now=()=>performance.now();
const auth=user=>({Authorization:'Basic '+Buffer.from(user+':'+(user==='admin'?'admin123':'student123')).toString('base64')});
function sql(s){let r=cp.spawnSync('docker',['exec','-i','mindbridge-mysql','sh','-c',`exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ${db} --batch --raw --skip-column-names --default-character-set=utf8mb4`],{input:s,encoding:'utf8',maxBuffer:32*1024*1024});if(r.status)throw Error(r.stderr);return r.stdout.trim();}
function rows(table,fields,where='1=1'){return sql(`SELECT JSON_OBJECT(${fields.map(f=>`'${f}',${f}`).join(',')}) FROM ${table} WHERE ${where};`).split('\n').filter(Boolean).map(JSON.parse);}
async function get(url,user='admin'){let r=await fetch(base+url,{headers:auth(user),signal:AbortSignal.timeout(20000)});if(!r.ok)throw Error(url+' HTTP '+r.status);return r.json();}
const resultPrefix=process.env.BENCH_RESULT_PREFIX||'';
const logFile=dir+'/'+resultPrefix+'results.jsonl';
const prior=()=>fs.existsSync(logFile)?fs.readFileSync(logFile,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse):[];
function progress(done,active=null){fs.writeFileSync(dir+'/progress.json',JSON.stringify({updatedAt:new Date(),completed:done.length,active,success:done.filter(x=>x.reply?.status==='SUCCESS').length,backgroundFailed:done.filter(x=>x.tasks?.some(t=>t.status==='FAILED')).length,averageReplyMs:done.length?Math.round(done.reduce((s,r)=>s+(r.replyMs||0),0)/done.length):null},null,2));}
async function main(){
 const config=JSON.parse(fs.readFileSync(dir+'/compose.json','utf8'));
 const e=config.services['bench-app'].environment,me=config.services['bench-mcp'].environment;
 const deploymentImage=cp.execFileSync('docker',['inspect','mindbridge-bench-app','--format','{{.Image}}'],{encoding:'utf8'}).trim();
 if(e.MAIL_PROVIDER!=='legacy'||e.MCP_EMAIL_MODE!=='log'||e.RESEND_API_KEY||me.RESEND_API_KEY||me.TOOLS_RESEND_BASE!=='http://127.0.0.1:1')throw Error('Unsafe email configuration');
 if((await get('/actuator/health')).status!=='UP')throw Error('App not ready');
 let samples=JSON.parse(fs.readFileSync(process.env.BENCH_SAMPLE_FILE||dir+'/samples.json','utf8'));
 if(!samples[0].turn){
  if(prior().length)throw Error('Cannot regroup after run');
  const groups=[];for(const label of ['正常','焦虑','低落','高风险']){const a=samples.filter(s=>s.label===label);for(let i=0;i<a.length;i+=5)groups.push(a.slice(i,i+5));}
  groups.sort((a,b)=>a[0].inputHash.localeCompare(b[0].inputHash));
  samples=groups.flatMap((g,i)=>g.map((r,j)=>({...r,index:i*5+j+1,username:'bench-'+String(i+1).padStart(4,'0'),turn:j+1})));
  fs.writeFileSync(dir+'/samples.json',JSON.stringify(samples,null,2));
 }
 sql([...new Set(samples.map(s=>s.username))].map(username=>`INSERT INTO user_accounts(username,password,display_name,enabled,created_at) SELECT '${username}',password,'Synthetic benchmark',1,NOW(6) FROM user_accounts WHERE username='student' AND NOT EXISTS (SELECT 1 FROM user_accounts WHERE username='${username}'); INSERT IGNORE INTO user_account_roles(user_id,role) SELECT id,'ROLE_USER' FROM user_accounts WHERE username='${username}' AND NOT EXISTS (SELECT 1 FROM user_account_roles r WHERE r.user_id=user_accounts.id AND r.role='ROLE_USER');`).join('\n'));
 const done=prior(),seen=new Set(done.map(r=>r.requestId)),sessions=new Map(done.filter(r=>r.reply?.sessionId).map(r=>[r.username,r.reply.sessionId]));
 const limit=Number(process.argv.find(a=>a.startsWith('--limit='))?.split('=')[1]||1000);
 const selectedRanges=process.argv.find(a=>a.startsWith('--ranges='))?.split('=')[1].split(',').map(x=>x.split('-').map(Number));
 for(const s of samples.slice(0,limit).filter(s=>!selectedRanges||selectedRanges.some(([a,b])=>s.index>=a&&s.index<=(b||a)))){
  if(seen.has(s.requestId))continue;
  const start=now(),result={...s,deploymentImage,startedAt:new Date().toISOString(),events:[],firstTokenMs:null};
  progress(done,{index:s.index,requestId:s.requestId,startedAt:result.startedAt});
  try{
   const r=await fetch(base+'/api/chat/stream',{method:'POST',headers:{...auth(s.username),'Content-Type':'application/json'},body:JSON.stringify({message:s.input,requestId:s.requestId,sessionId:sessions.get(s.username)||null}),signal:AbortSignal.timeout(450000)});
   if(!r.ok)throw Error('SSE HTTP '+r.status);
   let buffer='',decoder=new TextDecoder();
   for await(const part of r.body){buffer+=decoder.decode(part,{stream:true});let end;while((end=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,end).replaceAll('\r','');buffer=buffer.slice(end+2);const data=frame.split('\n').filter(x=>x.startsWith('data:')).map(x=>x.slice(5)).join('\n');if(!data)continue;const event=JSON.parse(data);if(event.type==='token'&&result.firstTokenMs===null)result.firstTokenMs=Math.round(now()-start);if(event.type!=='token'&&event.type!=='meta'&&event.type!=='heartbeat')result.events.push({ms:Math.round(now()-start),...event});}}
   result.replyMs=Math.round(now()-start);
   result.reply=await get('/api/chat/replies/'+s.requestId,s.username);
   if(result.reply.sessionId)sessions.set(s.username,result.reply.sessionId);
  }catch(err){result.error=err.name+': '+err.message;result.replyMs=Math.round(now()-start);try{result.reply=await get('/api/chat/replies/'+s.requestId,s.username);if(result.reply.sessionId)sessions.set(s.username,result.reply.sessionId);}catch{}}
  const drain=now();
  while(true){
   result.tasks=rows('memory_tasks',['id','kind','status','attempts','created_at','updated_at','error_type','result_note'],`request_id='${s.requestId}'`);
   result.tools=rows('report_tasks t JOIN psychological_reports p ON p.id=t.report_id JOIN user_accounts u ON u.id=p.user_id',['t.id','t.kind','t.status','t.attempts','t.provider','t.delivery_status','t.error'],`u.username='${s.username}'`);
   if(result.tools.some(t=>t['t.kind']==='MAIL'&&t['t.provider']&&t['t.provider']!=='LOG'))throw Error('Unexpected non-LOG mail task');
   const pending=result.tasks.some(t=>['PENDING','RETRYING','RUNNING'].includes(t.status))||result.tools.some(t=>['PENDING','RETRYING','RUNNING'].includes(t['t.status']));
   if(!pending&&(result.tasks.length||result.reply?.status!=='SUCCESS'))break;
   if(now()-drain>240000){result.backgroundTimeout=true;break;}
   await pause(1000);
  }
  result.allWorkMs=Math.round(now()-start);result.postReplyWaitMs=Math.round(now()-drain);
  result.steps=rows('agent_execution_steps',['step_number','agent','action','status','attempts','duration_ms','error_type','observation'],`request_id='${s.requestId}' ORDER BY step_number`);
  result.checkpoints=rows('agent_checkpoints',['status','completed_steps','revision','snapshot_json','created_at','updated_at','error_type'],`request_id='${s.requestId}'`).map(c=>{const snap=JSON.parse(c.snapshot_json||'{}');delete c.snapshot_json;return {...c,intent:snap.intent,risk:snap.riskLevel,knowledgeStatus:snap.knowledgeStatus,knowledgePackId:snap.knowledgePackId,knowledgeCount:snap.retrievedKnowledge?.length,historyCount:snap.previousHistory?.length,finished:snap.finished};});
  result.calls=(await get('/api/admin/model-calls?requestId='+s.requestId)).records;
  if(result.reply?.sessionId){const key='mindharbor:chat:short-memory:'+result.reply.sessionId;result.redisMessages=Number(cp.execFileSync('docker',['exec','mindbridge-bench-redis','redis-cli','LLEN',key],{encoding:'utf8'}).trim());}
  fs.appendFileSync(logFile,JSON.stringify(result)+'\n');done.push(result);progress(done);
  console.log(JSON.stringify({completed:done.length,index:s.index,label:s.label,turn:s.turn,reply:result.reply?.status,firstTokenMs:result.firstTokenMs,replyMs:result.replyMs,allWorkMs:result.allWorkMs,background:result.tasks.map(t=>t.kind+':'+t.status),route:result.checkpoints[0]?.intent,risk:result.checkpoints[0]?.risk}));
  if(process.argv.includes('--stop-on-failure')&&(result.reply?.status!=='SUCCESS'||result.backgroundTimeout||result.tasks.some(t=>t.status==='FAILED')||result.tools.some(t=>['FAILED','UNKNOWN'].includes(t['t.status']))||(s.label==='高风险'&&result.checkpoints[0]?.intent==='CHAT')))
   throw Error('Batch paused for diagnosis at sample '+s.index+'; evidence already saved');
 }
 fs.writeFileSync(dir+'/run-state.json',JSON.stringify({status:done.length===1000?'COMPLETE':'PARTIAL',completed:done.length,finishedAt:new Date()},null,2));
}
main().catch(e=>{fs.writeFileSync(dir+'/runner-error.txt',e.stack);console.error(e);process.exitCode=1;});
