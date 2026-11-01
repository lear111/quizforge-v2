import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readdir,readFile,access} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {execFileSync} from 'node:child_process';

const root=fileURLToPath(new URL('../public/admin/',import.meta.url));

async function files(directory) {
  const result=[];
  for(const item of await readdir(directory,{withFileTypes:true})) {
    const path=resolve(directory,item.name);
    if(item.isDirectory())result.push(...await files(path));else if(path.endsWith('.js'))result.push(path);
  }
  return result;
}

test('admin modules parse and every relative import exists',async()=>{
  for(const path of await files(root)) {
    execFileSync(process.execPath,['--check',path]);
    const source=await readFile(path,'utf8');
    for(const match of source.matchAll(/(?:from\s+|import\()\s*['"](\.[^'"]+)['"]/g))await access(resolve(dirname(path),match[1]));
  }
});

test('the navigation registry has unique ids and every page exports render',async()=>{
  const {pages,defaultPage}=await import(pathToFileURL(resolve(root,'pages/registry.js')).href);
  const ids=pages.map(page=>page.id);
  assert.equal(new Set(ids).size,ids.length);
  assert.ok(ids.includes(defaultPage));
  for(const page of pages) {
    assert.match(page.id,/^[a-z0-9-]+$/);
    assert.ok(page.label&&page.group,page.id);
    const source=await readFile(resolve(root,'pages',`${page.load.toString().match(/import\('\.\/([^']+)'\)/)[1]}`),'utf8');
    assert.match(source,/export async function render\(container, context\)/,page.id);
  }
  assert.deepEqual(ids,['users','authentication','invitations','audit']);
});

test('admin pages never write HTML strings into the document',async()=>{
  for(const path of await files(root)) {
    const source=await readFile(path,'utf8');
    for(const forbidden of ['innerHTML','outerHTML','insertAdjacentHTML','document.write'])assert.ok(!source.includes(forbidden),`${path}: ${forbidden}`);
  }
});
