// Supervises the long real-model run and refreshes artifacts without another chat turn.
const fs=require('node:fs'),cp=require('node:child_process');
const dir='data/benchmarks/full-flow-20261023';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function read(name){try{return JSON.parse(fs.readFileSync(dir+'/'+name,'utf8'))}catch{return null}}
function refresh(){const r=cp.spawnSync(process.execPath,['scripts/report-full-flow-benchmark.cjs'],{encoding:'utf8'});console.log(r.stdout||r.stderr);}
(async()=>{
 fs.writeFileSync(dir+'/supervisor.json',JSON.stringify({pid:process.pid,startedAt:new Date(),status:'WAITING_FOR_PILOT'}));
 while(read('pilot-acceptance.json')?.status!=='PASS'){if(fs.existsSync(dir+'/runner-error.txt'))throw Error('Pilot failed; see runner-error.txt');await sleep(5000);}
 refresh();
 const out=fs.openSync(dir+'/batch.stdout.log','a'),err=fs.openSync(dir+'/batch.stderr.log','a');
 const child=cp.spawn(process.execPath,['scripts/run-full-flow-benchmark.cjs','--limit=1000','--stop-on-failure'],{stdio:['ignore',out,err],windowsHide:true});
 fs.writeFileSync(dir+'/supervisor.json',JSON.stringify({pid:process.pid,runnerPid:child.pid,startedAt:new Date(),status:'RUNNING'}));
 let lastCount=0;const timer=setInterval(()=>{const p=read('progress.json');if(p?.completed!==lastCount){lastCount=p.completed;refresh();console.log(new Date().toISOString(),JSON.stringify(p));}},60000);
 const code=await new Promise(resolve=>child.once('exit',resolve));clearInterval(timer);refresh();
 if(code!==0)throw Error('Runner stopped with exit '+code);
 const excel=cp.spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File','scripts/verify-benchmark-excel.ps1'],{encoding:'utf8',windowsHide:true});
 console.log(excel.stdout||excel.stderr);
 if(excel.status!==0)throw Error('Excel verification failed');
 fs.writeFileSync(dir+'/supervisor.json',JSON.stringify({pid:process.pid,completedAt:new Date(),status:read('summary.json')?.complete?'COMPLETE':'INCOMPLETE',excelExitCode:excel.status}));
})().catch(e=>{fs.writeFileSync(dir+'/supervisor-error.txt',e.stack);
 fs.writeFileSync(dir+'/supervisor.json',JSON.stringify({pid:process.pid,status:'PAUSED_FOR_DIAGNOSIS',at:new Date(),error:e.message}));
 console.error(e);process.exitCode=1});
