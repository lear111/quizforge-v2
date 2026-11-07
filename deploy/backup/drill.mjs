// 恢复演练的数据步骤（docs/ops/03-restore-drill.md）。只允许本机地址和 qf- 开头的隔离项目。
//   node deploy/backup/drill.mjs seed <状态文件>    写入数据：账号、公开题库、作答、历史
//   node deploy/backup/drill.mjs check <状态文件>   恢复后检查：账号能登录、题库能打开、作答与历史还在、广场下载逐字节一致
import {execFileSync} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';

const [,,command,stateFile]=process.argv;
const base=process.env.QF_DRILL_BASE_URL??'';
const project=process.env.QF_COMPOSE_PROJECT??'';
const composeArgs=(process.env.QF_COMPOSE_ARGS??'').split(' ').filter(Boolean);
if(!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(base))throw Error('QF_DRILL_BASE_URL must be a local address');
if(!project.startsWith('qf-'))throw Error('QF_COMPOSE_PROJECT must be an isolated qf-* project');
process.env.NODE_TLS_REJECT_UNAUTHORIZED='0'; // 本机自签证书。
const sha256=value=>createHash('sha256').update(value).digest('hex');
const compose=(...args)=>execFileSync('docker',['compose','-p',project,...composeArgs,...args],{encoding:'utf8',maxBuffer:1<<26,stdio:['ignore','pipe','pipe']});
const sql=query=>compose('exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N "$MYSQL_DATABASE" -e "$1"','--',query);
const sleep=ms=>new Promise(done=>setTimeout(done,ms));

class Session {
  cookie='';
  async call(method,path,body,extra={}){
    const headers={Origin:base,...extra.headers};
    if(this.cookie)headers.Cookie=this.cookie;
    let payload=body;
    if(body&&!(body instanceof FormData)){headers['Content-Type']='application/json';payload=JSON.stringify(body);}
    const response=await fetch(base+path,{method,headers,body:payload,redirect:'manual'});
    const set=response.headers.getSetCookie().map(value=>value.split(';')[0]).filter(value=>value.startsWith('__Host-'));
    if(set.length)this.cookie=set.join('; ');
    return response;
  }
  async json(method,path,body,expected=[200]){
    const response=await this.call(method,path,body);
    const text=await response.text();
    assert.ok(expected.includes(response.status),`${method} ${path} -> ${response.status} ${text.slice(0,300)}`);
    return text?JSON.parse(text):null;
  }
}

async function register(label,name,password){
  const session=new Session();
  const email=`${label}@${randomUUID()}.example.test`;
  await session.json('POST','/api/auth/email-code',{identifier:email});
  const marker=`[开发验证码] ${email[0]}***${email.slice(email.indexOf('@'))} 注册验证码：`;
  let code;
  for(let i=0;i<60&&!code;i++){
    const line=compose('logs','app','--no-color','--since','10m').split('\n').filter(row=>row.includes(marker)).at(-1);
    if(line)code=line.slice(line.indexOf(marker)+marker.length).trim().slice(0,6);else await sleep(500);
  }
  assert.ok(code,'没有读到验证码');
  await session.json('POST','/api/auth/register',{identifier:email,name,password,code});
  return {session,email};
}

async function waitJob(session,base,accepted){
  if(!accepted?.jobId)return accepted;
  for(let i=0;i<240;i++){
    const job=await session.json('GET',`${base}/jobs/${accepted.jobId}`);
    if(job.status==='completed')return job.result??job;
    assert.notEqual(job.status,'failed',JSON.stringify(job));
    await sleep(500);
  }
  throw Error('后台任务没有完成');
}

async function seed(){
  const password=`Drill-${randomUUID()}`;
  const admin=await register('drill-admin','演练管理员',password);
  sql(`UPDATE account_user SET is_admin = TRUE WHERE email = '${admin.email}';`);
  const learner=await register('drill-user','演练用户',password);

  // 管理员发布一个公开题库包（样例文件）。
  const archive=readFileSync(new URL('../../samples/basic-types.qbank',import.meta.url));
  const form=new FormData();
  for(const [key,value] of Object.entries({title:`恢复演练 ${randomUUID().slice(0,8)}`,summary:'恢复演练',description:'恢复演练的公开题库。',category:'其他',tags:'测试',rights:'yes'}))form.set(key,value);
  form.set('archive',new Blob([archive]),'basic-types.qbank');
  const {packId}=await admin.session.json('POST','/api/catalog/packs',form,[201]);
  let detail;
  for(let i=0;i<240;i++){
    const reviews=await admin.session.json('GET','/api/admin/catalog/reviews');
    if(reviews.items.some(item=>item.packId===packId&&item.revision===1))break;
    if((await admin.session.call('GET',`/api/plaza/packs/${packId}`)).status===200)break;
    await sleep(500);
  }
  if((await admin.session.call('GET',`/api/plaza/packs/${packId}`)).status!==200){
    detail=await admin.session.json('GET',`/api/admin/catalog/packs/${packId}/revisions/1`);
    for(const extension of detail.extensions.filter(row=>row.status!=='approved'))await admin.session.json('POST',`/api/admin/extensions/${extension.digest}/approve`);
    await admin.session.json('POST',`/api/admin/catalog/packs/${packId}/revisions/1/approve`,undefined,[204]);
  }

  // 用户导入“判断题”，提交一道题，完成练习并再练一次（生成冻结历史），再提交一道题（当前作答）。
  const plaza=await learner.session.json('GET',`/api/plaza/packs/${packId}`);
  const bank=plaza.banks.find(row=>row.title==='判断题');
  const {collections:[{id:collection}]}=await learner.session.json('POST',`/api/plaza/packs/${packId}/imports`,{banks:[bank.uuid]});
  const path=`/api/collections/bank/${collection}`;
  const overview=await learner.session.json('GET',path);
  const qid=overview.questions[0].id;
  const submit=async()=>{
    const question=await learner.session.json('GET',`${path}/questions/${qid}`);
    return learner.session.json('POST',`${path}/questions/${qid}/actions`,{requestId:randomUUID(),revision:question.state?.revision??0,action:'submit',data:{answer:{value:true}}});
  };
  await submit();
  const lifecycle=async name=>{
    const summary=await learner.session.json('GET',`${path}/summary`);
    const accepted=await learner.session.json('POST',`${path}/${name}`,{requestId:randomUUID(),roundId:summary.roundId,summaryVersion:summary.summaryVersion},[200,202]);
    return waitJob(learner.session,path,accepted);
  };
  await lifecycle('finish');
  await lifecycle('restart');
  await submit();
  const history=await learner.session.json('GET',`${path}/history`);
  const state=await learner.session.json('GET',`${path}/questions/${qid}`);
  const items=Array.isArray(history)?history:Object.values(history).find(Array.isArray)??[];
  assert.ok(items.length>=1,`没有历史记录：${JSON.stringify(history).slice(0,200)}`);
  const historyId=items[0].id??items[0].historyId??items[0].roundId;
  assert.ok(historyId,`历史记录没有 ID：${JSON.stringify(items[0]).slice(0,200)}`);
  const frozen=await learner.session.json('GET',`${path}/history/${historyId}`);
  const manifest=await learner.session.json('GET',`/api/packs/${packId}/local-import?revision=1`);
  const value={email:learner.email,password,packId,collection,qid,sha256:sha256(archive),size:archive.length,manifest,
    submission:state.state,historyId,history:frozen,title:overview.title,users:Number(sql('SELECT COUNT(*) FROM account_user;').trim())};
  writeFileSync(stateFile,JSON.stringify(value,null,2));
  console.log(`写入完成：账号 ${value.users} 个，题库包 ${packId}，题库实例 ${collection}，历史 ${historyId}`);
}

async function check(){
  const value=JSON.parse(readFileSync(stateFile,'utf8'));
  const results=[];
  const ok=(name,detail='')=>{results.push(name);console.log(`通过：${name}${detail?`（${detail}）`:''}`);};
  // 账号能登录（原密码）。
  const session=new Session();
  await session.json('POST','/api/auth/login',{identifier:value.email,password:value.password});
  const me=await session.json('GET','/api/me');
  assert.equal(me.user?.email??me.email,value.email);
  ok('账号能用原密码登录');
  assert.equal(Number(sql('SELECT COUNT(*) FROM account_user;').trim()),value.users);
  ok('账号数量一致',String(value.users));
  // 题库能打开。
  const path=`/api/collections/bank/${value.collection}`;
  const overview=await session.json('GET',path);
  assert.equal(overview.title,value.title);
  const question=await session.json('GET',`${path}/questions/${value.qid}`);
  ok('题库能打开，题目能读取');
  // 作答还在。
  assert.equal(question.state.status,'submitted');
  assert.deepEqual(question.state.answer,value.submission.answer);
  assert.equal(question.state.revision,value.submission.revision);
  ok('当前作答还在',`revision ${question.state.revision}`);
  // 历史还在，且与备份前完全相同。
  const frozen=await session.json('GET',`${path}/history/${value.historyId}`);
  // 逐项比较（不比较序列化后的哈希，键顺序不同不算差异），失败时列出不同的位置。
  const differences=[];
  const compare=(a,b,at)=>{
    if(a&&b&&typeof a==='object'&&typeof b==='object'){
      for(const key of new Set([...Object.keys(a),...Object.keys(b)]))compare(a[key],b[key],`${at}.${key}`);
    }else if(a!==b)differences.push(`${at}: ${JSON.stringify(b)} → ${JSON.stringify(a)}`);
  };
  compare(frozen,value.history,'history');
  assert.deepEqual(differences,[],`冻结历史与备份前不同：${differences.slice(0,20).join('；')}`);
  ok('冻结历史与备份前完全相同');
  // 广场下载逐字节一致（匿名，经过 Nginx）。
  const anonymous=new Session();
  const plaza=await fetch(`${base}/api/plaza/packs/${value.packId}/archive`);
  assert.equal(plaza.status,200);
  const body=Buffer.from(await plaza.arrayBuffer());
  assert.equal(body.length,value.size);
  assert.equal(sha256(body),value.sha256);
  ok('广场下载逐字节一致',`${body.length} 字节，SHA-256 ${value.sha256.slice(0,12)}…`);
  const pinned=await fetch(`${base}/api/packs/${value.packId}/download?revision=1&sha256=${value.sha256}`);
  assert.equal(pinned.status,200);
  assert.equal(sha256(Buffer.from(await pinned.arrayBuffer())),value.sha256);
  ok('客户端固定版本下载逐字节一致');
  assert.deepEqual(await anonymous.json('GET',`/api/packs/${value.packId}/local-import?revision=1`),value.manifest);
  ok('客户端固定版本清单不变');
  // 恢复之后还能写入。
  const before=question.state.revision;
  const retried=await session.json('POST',`${path}/questions/${value.qid}/actions`,{requestId:randomUUID(),revision:before,action:'retry',data:{}});
  assert.ok(retried);
  ok('恢复后可以继续写入');
  console.log(`全部通过（${results.length} 项）`);
}

if(command==='seed')await seed();else if(command==='check')await check();else throw Error('用法：drill.mjs seed|check <状态文件>');
