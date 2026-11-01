import {test} from 'node:test';
import assert from 'node:assert/strict';
import {bindSaveRecovery} from '../practice/practice/save-recovery.js';

test('historical note recovery reloads the frozen record without loading current practice',async()=>{
  let loaded=0,cleared=0;
  const button={addEventListener(){},removeEventListener(){}};
  const pane={view:'history',questionId:'live-q',historyQuestionId:'old-q',node:{},plugin:{setActive(){}},writes:{async settled(){},clear(){cleared++;}},ink:{clear(){cleared++;}}};
  const recovery=bindSaveRecovery({document:{querySelector:()=>button},active:()=>pane,transitionTo:fn=>fn(),
    loadQuestion:()=>{throw Error('must not load current bank');},showQuestion:()=>{throw Error('must not leave history');},
    loadHistory:async value=>{assert.equal(value,pane);loaded++;},notice(){},collectionFeature:()=>true,confirm:()=>true});
  await recovery.loadSaved();assert.equal(loaded,1);assert.equal(cleared,2);assert.equal(pane.recovering,false);recovery.destroy();
});
