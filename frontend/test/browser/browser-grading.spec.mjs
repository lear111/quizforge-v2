import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';
import {uploadAndImportFromLibrary} from './library-fixture.mjs';

test.beforeEach(()=>test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立的本机 Compose'));

test('五种基础题型在真实浏览器与服务器中判分一致，先锁定再读取本题原文',async({page})=>{
  test.setTimeout(180000);
  const {origin}=await registerByApi(page,'browser-grade','本地判分');
  const {collections}=await uploadAndImportFromLibrary(page);
  // 判分只在练习空间运行：在 /practice/ 下的文档中执行，使用练习空间的 CSP（docs/ops/01-deploy.md 第 4 节）。
  await page.goto('/practice/grading/browser-rules.js');
  // 只读取仓库内的合成样例，避免把真实题库或答案写进测试产物。
  const banks=JSON.parse(execFileSync(process.platform==='win32'?'python':'python3',['-c',
    "import json,zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); print(json.dumps([dict(json.loads(z.read(n)),folder=n.split('/')[-2]) for n in z.namelist() if n.endswith('/bank.json')]))",
    resolve('../samples/basic-types.qbank')],{encoding:'utf8'}));
  for(const collection of collections) {
    const bank=banks.find(b=>b.folder===collection.title),question=bank.questions[0],data=question.data;
    let answer;
    if(collection.title==='单选题')answer={selectedOptionId:data.correctOptionId};
    else if(collection.title==='多选题')answer={selectedOptionIds:data.correctOptionIds};
    else if(collection.title==='判断题')answer={value:data.correctAnswer};
    else answer={responses:Object.fromEntries(data.parts.map(part=>[part.id,collection.title==='简答题'
      ?{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'缓存减少计算'}]}]}
      :{type:'qf-answer-area',formatVersion:1,id:`response-${part.id}`,width:760,height:part.answerHeight||480,strokes:[],images:[],text:'缓存减少计算'}]))};
    const base=`/api/collections/bank/${collection.id}`,qid=question.id;
    expect((await page.request.get(`${base}/grading/pending?questionId=${qid}`)).status()).toBe(409);
    const locked=await page.request.post(`${base}/questions/${qid}/actions`,{headers:{Origin:origin},data:{
      action:'submit',requestId:crypto.randomUUID(),revision:0,data:{answer}
    }});
    expect(locked.status(),await locked.text()).toBe(200);
    const ticket=(await locked.json()).browserGrading;
    expect(ticket.data).toEqual(data);
    const actual=await page.evaluate(async value=>(await import('/practice/grading/browser-rules.js')).executeBrowserGrade(value),ticket);
    const body={protocolVersion:1,requestId:crypto.randomUUID(),op:'submit',deadlineMs:10000,package:ticket.package,
      input:{data:ticket.data,answer,withScore:true}};
    const response=JSON.parse(execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),
      'exec','-T','rule-runner','node','--input-type=module','-e',
      "const r=await fetch('http://127.0.0.1:8090/v1/execute',{method:'POST',headers:{'Content-Type':'application/json','X-Internal-Token':process.env.QF_RULE_RUNNER_TOKEN},body:process.argv[1]});console.log(await r.text());",
      JSON.stringify(body)],{encoding:'utf8'}));
    expect(response.ok).toBe(true);expect(actual).toEqual(response.data);
    const saved=await page.request.post(`${base}/grading/reports`,{headers:{Origin:origin},data:{items:[{questionId:qid,generation:ticket.generation,outcome:actual}]}});
    expect(saved.status(),await saved.text()).toBe(200);
    const after=(await saved.json()).items[0];
    expect(after.state.result.gradingStatus).toBe(actual.score.gradingStatus);
    expect(after.state.result.score).toBe(actual.score.score);
  }
});

test('规则隔离禁止宿主权限，超时和超大输出后仍能执行下一道题',async({page})=>{
  await page.goto('/practice/grading/browser-rules.js');
  const values=await page.evaluate(async()=>{
    const {executeBrowserGrade}=await import('/practice/grading/browser-rules.js');
    const run=source=>executeBrowserGrade({source,package:{apiVersion:{major:1,minor:5}},data:{},answer:{}});
    const prefix='QF.defineType({grade(){return {score:1,maxScore:1,correct:true,feedback:""}},';
    const safe=prefix+'project(){return {cookie:typeof document,storage:typeof localStorage,network:typeof fetch,node:typeof process,require:typeof require,wasm:typeof WebAssembly}},getScore(){return {score:1,maxScore:1}}});';
    const first=await run(safe),failures=[];
    for(const source of [
      'QF.defineType({grade(){while(true){}},project(){return {}}});',
      prefix+'project(){return {large:"x".repeat(2*1024*1024)}},getScore(){return {score:1,maxScore:1}}});'
    ]) {try{await run(source);failures.push(false);}catch{failures.push(true);}}
    const fresh=await run('let leftover;QF.defineType({grade(){leftover=99;return {score:1,maxScore:1,correct:true,feedback:""}},project(){return {}},getScore(){return {score:leftover===undefined?1:0,maxScore:1}}});');
    return {first,failures,fresh,last:await run(safe)};
  });
  expect(Object.values(values.first.projected)).toEqual(Array(6).fill('undefined'));
  expect(values.failures).toEqual([true,true]);expect(values.fresh.score.score).toBe(1);
  expect(values.last).toEqual(values.first);
});
