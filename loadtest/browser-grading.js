// k6 没有 DOM / Worker。此适配器只运行本机仓库合成样例，用于 HTTP 性能比较；
// 不作为生产规则沙箱，真实浏览器的隔离与一致性由 Playwright 另行验证。
export function gradeFixture(ticket) {
  function realm() {
    let type;
    const QF={api:{major:1,minor:5,capabilities:['score','manual-review','outline-items','answer-state','answer-areas','richtext']},defineType:value=>{type=value;}};
    new Function('QF',ticket.source)(QF);
    return type;
  }
  const type=realm(),data=ticket.data,answer=ticket.answer;
  if(type.validateQuestion?.(data)===false||type.validateAnswer?.(answer,data)===false)throw Error('合成样例作答无效');
  const result=type.grade(data,answer),projected=type.project(data,{submitted:true,result}),fresh=realm();
  fresh.validateQuestion?.(data);
  const value=fresh.getScore?fresh.getScore(data,{status:'submitted',submitted:true,answer,result}):result;
  const score={score:value.score,maxScore:value.maxScore,gradingStatus:result.gradingStatus||'graded'};
  if(Object.hasOwn(value,'outlineStates'))score.outlineStates=value.outlineStates;
  return {result,projected,score};
}
