const fs=require('node:fs');
const dir='data/benchmarks/full-flow-20261023',root='/api/v2/tenants/default_tenant/databases/default_database/collections';
async function call(port,url,body){const r=await fetch(`http://localhost:${port}${root}${url}`,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});if(!r.ok)throw Error(r.status+':'+await r.text());return r.json();}
(async()=>{
 const config=JSON.parse(fs.readFileSync('data/deploy/20261020-tag-packs/compose.json','utf8'));
 const c=await call(8000,'/'+config.services.app.environment.CHROMA_COLLECTION);
 const data=await call(8000,'/'+c.id+'/get',{limit:10000,include:['documents','metadatas','embeddings']});
 const target=await call(18000,'',{name:'benchmark_knowledge',get_or_create:true,configuration:{hnsw:{space:'cosine'}}});
 for(let i=0;i<data.ids.length;i+=100){const batch={};for(const k of ['ids','documents','metadatas','embeddings'])batch[k]=data[k].slice(i,i+100);await call(18000,'/'+target.id+'/upsert',batch);}
 fs.writeFileSync(dir+'/vector-seed.json',JSON.stringify({sourceCollection:c.name,sourceCount:data.ids.length,targetCount:await call(18000,'/'+target.id+'/count'),seededAt:new Date()},null,2));
 console.log('Copied '+data.ids.length+' knowledge vectors to isolated Chroma');
})().catch(e=>{console.error(e);process.exitCode=1});
