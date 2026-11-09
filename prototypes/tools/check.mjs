import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
const root=path.resolve(import.meta.dirname,'..'),context={window:{}};
const read=file=>fs.readFileSync(path.join(root,file),'utf8');
vm.runInNewContext(read('assets/mock-data.js'),context);
const groups=context.window.PROTOTYPE.groups,entries=[];
for(const group of groups)for(const size of ['desktop','mobile'])entries.push(`${group.number}-${group.id}-${size}.html`);
assert.equal(entries.length,16);
let links=0;
for(const file of ['index.html',...entries]){
 const source=read(file);assert.match(source,/lang="zh-CN"/);assert.match(source,/name="viewport"/);
 for(const match of source.matchAll(/(?:href|src)="([^"]+)"/g)){
  const ref=match[1];assert(!/^(?:https?:|\/|javascript:|data:)/i.test(ref),`${file}: unexpected external/absolute resource`);
  if(ref.startsWith('#'))continue;
  const target=path.resolve(root,path.dirname(file),ref.split(/[?#]/)[0]);assert(target.startsWith(root+path.sep));assert(fs.existsSync(target),`${file}: ${ref}`);links++;
 }
}
for(const file of ['assets/mock-data.js','assets/screens.js']){
 const source=read(file);new vm.Script(source,{filename:file});
 assert.doesNotMatch(source,/\bfetch\s*\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|localStorage|sessionStorage|indexedDB|postMessage|\.contentWindow\b|\.cookie\b/,file);
 assert.doesNotMatch(source,/https?:\/\//,file);
}
assert.doesNotMatch(read('assets/prototype.css'),/@import|url\(/i);
const upstream=JSON.parse(read('UPSTREAM.json'));
for(const item of upstream.sources){const hash=createHash('sha256').update(fs.readFileSync(path.join(root,'assets/upstream',item.file))).digest('hex');assert.equal(hash,item.sha256,item.file);}
for(const file of ['README.md','DATA_CONTRACT.md','GAPS.md','VALIDATION.md'])assert(fs.existsSync(path.join(root,file)),file);
for(const group of groups){assert(read('DATA_CONTRACT.md').includes(`${group.number}-${group.id}-desktop.html`));assert(read('DATA_CONTRACT.md').includes(`${group.number}-${group.id}-mobile.html`));}
console.log(JSON.stringify({ok:true,groups:groups.length,entries:entries.length,gallery:1,localReferences:links,upstreamHashes:upstream.sources.length,noNetworkOrPersistence:true},null,2));
