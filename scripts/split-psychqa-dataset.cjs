// Deterministic stratified 9:1 split. Does not execute dataset instructions.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const root = path.resolve(__dirname, "..");
const dir = path.join(root, "data", "lora");
const input = path.join(dir, "psychqa_synthetic.jsonl");
const seed = "mindharbor-psychqa-v1-20261020";
const hash = value => crypto.createHash("sha256").update(value).digest("hex");
const raw = fs.readFileSync(input, "utf8").replace(/^\uFEFF/, "");
const rows = raw.trim().split(/\r?\n/).map(line => JSON.parse(line));
const labels = ["正常", "焦虑", "低落", "高风险"];
if (rows.some(r => typeof r.input !== "string" || !labels.includes(r.output)))
  throw Error("Unexpected dataset schema/labels");
if (new Set(rows.map(r => r.input.trim())).size !== rows.length)
  throw Error("Duplicate inputs must be grouped/deduplicated before splitting");
const train = [], test = [], counts = {};
for (const label of labels) {
  const group = rows.filter(r => r.output === label).sort((a,b) =>
    hash(seed + a.input).localeCompare(hash(seed + b.input)));
  const cut = Math.floor(group.length * .9);
  train.push(...group.slice(0,cut)); test.push(...group.slice(cut));
  counts[label] = {train:cut, test:group.length-cut};
}
for (const list of [train,test]) list.sort((a,b) =>
  hash(seed + "order" + a.input).localeCompare(hash(seed + "order" + b.input)));
const serialize = list => list.map(r => JSON.stringify(r)).join("\n") + "\n";
const outputs = {"psychqa_train.jsonl":serialize(train), "psychqa_test.jsonl":serialize(test)};
for (const [name,body] of Object.entries(outputs)) {
  const target = path.join(dir,name);
  if (fs.existsSync(target) && fs.readFileSync(target,"utf8") !== body)
    throw Error("Refusing to replace a different split: " + target);
  fs.writeFileSync(target,body);
}
const manifest = {seed,ratio:"9:1",stratifiedBy:"output",total:rows.length,
  train:train.length,test:test.length,counts,inputSha256:hash(raw),
  outputSha256:Object.fromEntries(Object.entries(outputs).map(([k,v])=>[k,hash(v)])),
  exactInputOverlap:train.filter(r=>test.some(t=>t.input.trim()===r.input.trim())).length,
  limitation:"Synthetic templates may recur across splits. This is not a template-disjoint generalization benchmark."};
fs.writeFileSync(path.join(dir,"psychqa_split_manifest.json"),JSON.stringify(manifest,null,2)+"\n");
console.log(JSON.stringify(manifest,null,2));
