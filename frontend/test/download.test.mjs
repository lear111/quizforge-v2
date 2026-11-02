import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validItems,size} from '../public/download/download.js';

const sha='a'.repeat(64);
const item=(platform,file,extra={})=>({platform,version:'1.0.0',file,bytes:1024,sha256:sha,...extra});

test('only well-formed manifest entries are shown and Windows comes first',()=>{
  const items=validItems({schemaVersion:1,items:[
    item('android','QuizForge-1.0.0-android.apk'),
    item('windows','QuizForge-1.0.0-windows.exe'),
    item('windows','../etc/passwd'),
    item('ios','QuizForge.ipa'),
    item('windows','x.exe',{bytes:-1}),
    item('windows','y.exe',{sha256:'NOT-A-HASH'}),
    item('windows','.hidden.exe'),
    null,
  ]});
  assert.deepEqual(items.map(entry=>entry.file),['QuizForge-1.0.0-windows.exe','QuizForge-1.0.0-android.apk']);
  assert.deepEqual(validItems(null),[]);
  assert.deepEqual(validItems({items:'x'}),[]);
});

test('sizes are shown in KB below one megabyte and MB above',()=>{
  assert.equal(size(512*1024),'512.0 KB');
  assert.equal(size(85*1024*1024+512*1024),'85.5 MB');
});
