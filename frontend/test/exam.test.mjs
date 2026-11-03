import {test} from 'node:test';
import assert from 'node:assert/strict';
import {examTimeLabel,canStartExam} from '../practice/exam/exam-mode.js';
import {contextFor,examActive} from '../practice/practice/practice-context.js';

test('考试时间使用给定的服务器校准时间，截止后不出现负值',()=>{
  const now=Date.parse('2026-11-02T12:00:00Z');
  assert.equal(examTimeLabel({deadline:'2026-11-02T12:01:00Z'},now),'剩余 1:00');
  assert.equal(examTimeLabel({deadline:'2026-11-02T11:59:59Z'},now),'剩余 0:00');
  assert.equal(examTimeLabel({startedAt:'2026-11-02T11:58:00Z'},now),'已用 2:00');
});
test('题库考试开关控制入口，考试内不能再开考试',()=>{
  const pane={collection:{features:{exam:true}},kind:'bank',view:'practice'};
  assert.equal(canStartExam(pane),true);
  assert.equal(canStartExam({...pane,collection:{features:{exam:false}}}),false);
  assert.equal(canStartExam({...pane,exam:{status:'in-progress'}}),false);
});
test('考试作答仅开放草稿和白板；交卷后及完成后允许人工评分',()=>{
  const payload={question:{id:'q'},state:{status:'draft'},capabilities:{canSave:true,canSubmit:true,canRetry:true,canReview:true,canWhiteboard:true}};
  const pane={exam:{status:'in-progress'},payload,collection:{features:{whiteboard:true}},view:'practice'};
  const active=contextFor(pane).capabilities;
  assert.equal(active.canSave,true);assert.equal(active.canWhiteboard,true);
  assert.equal(active.canSubmit,false);assert.equal(active.canRetry,false);assert.equal(active.canReview,false);
  assert.equal(examActive({...pane,examDeadlineReached:true}),false);
  for(const status of ['grading','completed']){
    const locked=contextFor({...pane,exam:{status},payload:{...payload,state:{status:'submitted'}}}).capabilities;
    assert.equal(locked.canSave,false);assert.equal(locked.canWhiteboard,false);assert.equal(locked.canReview,true);
  }
});
