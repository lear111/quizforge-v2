import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {contextFor,canViewDraft} from '../practice/practice/practice-context.js';
import {prepareExtensionAssets} from '../practice/bridge/page-assets.js';

test('read-only backend capabilities disable writes without disabling the question',()=>{
  const pane={view:'practice',questionId:'q1',collection:{features:{whiteboard:true}},payload:{question:{id:'q1',data:{stem:'shown'}},state:{status:'unanswered',revision:0},capabilities:{canSave:false,canSubmit:false,canRetry:false,canWhiteboard:false}}};
  const context=contextFor(pane);
  assert.equal(context.question.data.stem,'shown');
  for(const name of ['canSave','canSubmit','canRetry','canWhiteboard'])assert.equal(context.capabilities[name],false);
  assert.equal(canViewDraft(pane),false);
});

test('answer-area pages load the authorized versioned provider once at the shared boundary',async()=>{
  const calls=[],provider={version:'1.4.1',script:'provider',style:'style'};
  const page=await prepareExtensionAssets({apiVersion:{major:1,minor:5},dependencies:[{id:'quizforge.richtext',version:'1.1.3'}],answerAreasDeclared:true,answerAreaVersion:'1.4.1'},async dependency=>{
    calls.push(dependency);return dependency.id==='quizforge.answer-area'?provider:{script:'richtext',style:'richtext'};
  });
  assert.deepEqual(calls,[{id:'quizforge.richtext',version:'1.1.3'},{id:'quizforge.answer-area',version:'1.4.1'}]);
  assert.equal(page.answerAreaComponent,provider);assert.equal(page.libraries.length,1);
});

test('frozen pages keep their embedded answer-area provider',async()=>{
  const provider={version:'1.4.1',script:'frozen',style:''};
  const page=await prepareExtensionAssets({apiVersion:{major:1,minor:5},answerAreasDeclared:true,answerAreaComponent:provider},()=>{throw Error('unexpected SDK fetch');});
  assert.equal(page.answerAreaComponent,provider);
});

test('read-only practice flags do not suppress frozen history paper view',()=>{
  const pane={view:'history',payload:{capabilities:{canWhiteboard:false}},historyQuestion:{payload:{}},historyEntry:{collection:{features:{whiteboard:true}}}};
  assert.equal(canViewDraft(pane),true);
});

test('generated SDK JSON contains compilable pinned providers and styles',async()=>{
  for(const file of ['quizforge.richtext/1.0.0','quizforge.richtext/1.0.0-editor','quizforge.richtext/1.1.3','quizforge.richtext/1.1.3-editor','quizforge.answer-area/1.4.1']){
    const sdk=JSON.parse(await readFile(new URL('../.generated-sdk/'+file+'.json',import.meta.url),'utf8'));
    assert.ok(sdk.style.length>0);assert.ok(sdk.script.length>0);new vm.Script(sdk.script);
    if(file.startsWith('quizforge.answer-area')){
      const sandbox={};vm.runInNewContext(sdk.script,sandbox);
      assert.equal(sandbox.__qfAnswerAreaProvider.version,'1.4.1');
    }
  }
});
