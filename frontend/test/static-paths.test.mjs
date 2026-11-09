import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir} from 'node:fs/promises';

test('所有页面的脚本、样式与图片使用站点绝对路径',async()=>{
  for(const folder of ['public','practice']){
    const root=new URL('../'+folder+'/',import.meta.url);
    for(const item of await readdir(root,{recursive:true})){
      if(!item.endsWith('.html'))continue;
      const html=await readFile(new URL(item.replaceAll('\\','/'),root),'utf8');
      for(const match of html.matchAll(/<(?:script|link|img)\b[^>]*\b(?:src|href)="([^"]+)"/gi)){
        assert.ok(match[1].startsWith('/')||match[1].startsWith('data:'),`${folder}/${item}: ${match[1]}`);
      }
    }
  }
});
