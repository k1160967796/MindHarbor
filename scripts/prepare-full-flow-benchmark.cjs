// Creates an isolated test deployment. Never copies production mail credentials.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),dir=path.join(root,'data','benchmarks','full-flow-20261023');
fs.mkdirSync(dir,{recursive:true});
const source=JSON.parse(fs.readFileSync(path.join(root,'data/deploy/20261020-tag-packs/compose.json'),'utf8'));
const original=source.services.app.environment,db='mindbridge_e2e_20261023';
function sql(s){const r=cp.spawnSync('docker',['exec','-i','mindbridge-mysql','sh','-c','exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --raw --skip-column-names'],{input:s,encoding:'utf8'});if(r.status)throw Error(r.stderr);return r.stdout.trim();}
const sourceDb=original.DB_URL.match(/3306\/([^?]+)/)[1];
if(sql(`SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${db}';`)!=='0')throw Error('Test database already exists; reuse existing setup, do not reset');
const quote=s=>"'"+s.replaceAll('\\','\\\\').replaceAll("'","''")+"'";
sql(`CREATE DATABASE ${db} CHARACTER SET utf8mb4; GRANT ALL ON ${db}.* TO ${quote(original.DB_USERNAME)}@'%';
CREATE TABLE ${db}.knowledge_chunks LIKE ${sourceDb}.knowledge_chunks;
INSERT INTO ${db}.knowledge_chunks SELECT * FROM ${sourceDb}.knowledge_chunks;
CREATE TABLE ${db}.reviewed_knowledge_packs LIKE ${sourceDb}.reviewed_knowledge_packs;
INSERT INTO ${db}.reviewed_knowledge_packs SELECT * FROM ${sourceDb}.reviewed_knowledge_packs;`);
const allowed=['SPRING_PROFILES_ACTIVE','DB_USERNAME','DB_PASSWORD','JAVA_TOOL_OPTIONS','AI_PROVIDER','OLLAMA_MODEL','OLLAMA_BASE_URL','OPENAI_BASE_URL','OPENAI_EMBEDDING_MODEL','RAG_RERANKER_MAX_CONTENT_CHARS','USE_CHROMA','MEMORY_USE_CHROMA'];
const env=Object.fromEntries(allowed.filter(k=>original[k]!=null).map(k=>[k,original[k]]));
const token=crypto.randomBytes(32).toString('hex');
Object.assign(env,{SERVER_PORT:'8080',DB_URL:original.DB_URL.replace('/'+sourceDb+'?','/'+db+'?'),OPENAI_API_KEY:'ollama',
 REDIS_HOST:'bench-redis',REDIS_PORT:'6379',CHROMA_BASE_URL:'http://bench-chroma:8000',CHROMA_COLLECTION:'benchmark_knowledge',
 MEMORY_CHROMA_BASE_URL:'http://bench-chroma:8000',MEMORY_CHROMA_COLLECTION:'benchmark_memory',
 MCP_REMOTE_ENABLED:'true',MCP_REMOTE_URL:'http://bench-mcp:8081',MCP_SERVICE_TOKEN:token,MCP_EXCEL_MODE:'mcp',
 MCP_CLIENT_ENABLED:'false',MCP_SERVER_ENABLED:'false',MAIL_PROVIDER:'legacy',MCP_EMAIL_MODE:'log',
 MCP_EMAIL_SERVER_DELIVERY_MODE:'log',ALERT_MAIL_RECIPIENTS:'benchmark@example.invalid',ALERT_MAIL_FROM:'benchmark@example.invalid',
 ALERT_RECIPIENT_ACCOUNTS:'benchmark@example.invalid=admin',ALERT_MAX_REMINDERS:'0',ALERT_REMINDER_SECONDS:'600',
 MAIL_HOST:'127.0.0.1',MAIL_PORT:'1',MAIL_PROVIDER_API_BASE:'http://127.0.0.1:1',RESEND_API_KEY:'',RESEND_WEBHOOK_SECRET:'',
 MINDHARBOR_PUBLIC_URL:'http://localhost:18080',MINDHARBOR_KNOWLEDGE_REINDEX_ON_START:'false'});
for(const sub of ['app','mcp','chroma','redis'])fs.mkdirSync(path.join(dir,sub),{recursive:true});
const mount=(sub,target)=>({type:'bind',source:path.join(dir,sub),target});
const compose={name:'mindbridge-benchmark',services:{
 'bench-redis':{image:'redis:7.2-alpine',container_name:'mindbridge-bench-redis',command:['redis-server','--appendonly','yes'],volumes:[mount('redis','/data')]},
 'bench-chroma':{image:cp.execFileSync('docker',['inspect','mindbridge-chroma','--format','{{.Config.Image}}'],{encoding:'utf8'}).trim(),container_name:'mindbridge-bench-chroma',ports:['127.0.0.1:18000:8000'],volumes:[mount('chroma','/data')]},
 'bench-mcp':{image:'mindharbor-mcp-tools:local',container_name:'mindbridge-bench-mcp',environment:{MCP_SERVICE_TOKEN:token,RESEND_API_KEY:'',TOOLS_RESEND_BASE:'http://127.0.0.1:1',ALERT_MAIL_RECIPIENTS:'benchmark@example.invalid',ALERT_MAIL_FROM:'benchmark@example.invalid',JAVA_TOOL_OPTIONS:'-Xms64m -Xmx384m'},volumes:[mount('mcp','/app/data')]},
 'bench-app':{image:'mindharbor-agent:local',container_name:'mindbridge-bench-app',environment:env,ports:['127.0.0.1:18080:8080'],extra_hosts:['host.docker.internal:host-gateway'],volumes:[mount('app','/app/data')],depends_on:['bench-redis','bench-chroma','bench-mcp']}
},networks:{default:{external:true,name:'mindbridge_default'}}};
fs.writeFileSync(path.join(dir,'compose.json'),JSON.stringify(compose,null,2));
const rows=name=>fs.readFileSync(path.join(root,'data/lora',name),'utf8').trim().split(/\r?\n/).map(JSON.parse);
const hash=s=>crypto.createHash('sha256').update(s).digest('hex');
const train=rows('psychqa_train.jsonl'),test=rows('psychqa_test.jsonl'),selected=[];
for(const label of ['正常','焦虑','低落','高风险']){
 const held=test.filter(r=>r.output===label).map(r=>({...r,split:'test'}));
 const known=train.filter(r=>r.output===label).sort((a,b)=>hash('e2e-v1'+a.input).localeCompare(hash('e2e-v1'+b.input))).slice(0,250-held.length).map(r=>({...r,split:'train'}));
 selected.push(...held,...known);
}
selected.sort((a,b)=>hash('order-v1'+a.input).localeCompare(hash('order-v1'+b.input)));
const samples=selected.map((r,i)=>({index:i+1,input:r.input,label:r.output,split:r.split,inputHash:hash(r.input),requestId:crypto.randomUUID(),username:'bench-'+String(i+1).padStart(4,'0')}));
fs.writeFileSync(path.join(dir,'samples.json'),JSON.stringify(samples,null,2));
fs.writeFileSync(path.join(dir,'manifest.json'),JSON.stringify({createdAt:new Date(),seed:'e2e-v1',count:samples.length,labels:{正常:250,焦虑:250,低落:250,高风险:250},splits:{test:240,train:760},mode:'real local models; isolated DB/Redis/Chroma/Excel; mail LOG only',db,knowledgeRows:Number(sql(`SELECT COUNT(*) FROM ${db}.knowledge_chunks;`)),sourceImage:cp.execFileSync('docker',['image','inspect','mindharbor-agent:local','--format','{{.Id}}'],{encoding:'utf8'}).trim()},null,2));
console.log('Prepared isolated database, compose, and 1000 deterministic samples at '+dir);
