// 上线前容量复测（loadtest/CAPACITY.md）：M3 的混合练习场景 + 浏览器判分 + 导入期间的提交。
// 只能对 Compose 内部的 nginx 或本机回环地址运行（在被测服务器本机上运行，不经过公网）。
//
//   practice：VUS 个用户逐步加压（RAMP 内 0 → VUS，保持 HOLD，DOWN 内减到 0），每轮迭代：
//             打开题库 → 读题 → 思考 3～8 秒 → 保存草稿 2 次 → 提交（browser 模式下再上报判分结果）→ 看结果
//   imports： IMPORT_USERS 个用户在保持阶段每隔 IMPORT_EVERY 上传一次 loadtest-300.qbank 并等待后台校验完成
//
// 需要 seed.py 生成至少 VUS + IMPORT_USERS 个全新用户（每轮使用一道未提交的题，题目用完即失败）。
import http from 'k6/http';
import {check, fail, sleep} from 'k6';
import {Counter, Trend} from 'k6/metrics';
import exec from 'k6/execution';
import {gradeFixture} from './browser-grading.js';

const submitFlow = new Trend('qf_submit_flow', true);
const importDuration = new Trend('qf_import_duration', true);
const importsDone = new Counter('qf_imports_completed');
const importsRejected = new Counter('qf_imports_rejected');

const users = JSON.parse(open(__ENV.USERS_FILE || '/work/users.json'));
const archive = open(__ENV.IMPORT_ARCHIVE || '/samples/loadtest-300.qbank', 'b');
const base = __ENV.BASE_URL || 'http://nginx';
if (!/^http:\/\/(nginx|localhost|127\.0\.0\.1)(:\d+)?$/.test(base)) throw Error('只允许 Compose 内部的 nginx 或本机回环地址');
const origin = __ENV.ORIGIN || 'http://localhost:8099';
const vus = Number(__ENV.VUS || 100);
const importUsers = Number(__ENV.IMPORT_USERS || 0);
const ramp = __ENV.RAMP || '1m', hold = __ENV.HOLD || '3m', down = __ENV.DOWN || '30s';
if (!Number.isInteger(vus) || vus < 1) throw Error('VUS 必须是正整数');
if (!Number.isInteger(importUsers) || importUsers < 0) throw Error('IMPORT_USERS 必须是非负整数');
if (users.length < vus + importUsers) throw Error(`users.json 只有 ${users.length} 个用户，需要 ${vus + importUsers} 个（VUS + IMPORT_USERS）`);

const scenarios = {
  practice: {executor: 'ramping-vus', exec: 'practice', startVUs: 0, gracefulRampDown: '30s',
    stages: [{duration: ramp, target: vus}, {duration: hold, target: vus}, {duration: down, target: 0}]},
};
if (importUsers > 0) {
  // 在加压完成后开始，持续整个保持阶段：导入与满负载的提交重叠。
  scenarios.imports = {executor: 'constant-vus', exec: 'imports', vus: importUsers, startTime: ramp, duration: hold, gracefulStop: '3m'};
}

// 达标标准（CAPACITY.md 第 4 节）：不达标时 k6 以非 0 退出。
export const options = {
  scenarios, setupTimeout: '10m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{name:submit}': ['p(95)<1000'],
    'http_req_duration{name:draft}': ['p(95)<500'],
    'http_req_duration{name:read}': ['p(95)<500'],
    'http_req_duration{name:overview}': ['p(95)<2000'],
    qf_submit_flow: ['p(95)<1500'],
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

function params(cookie, name, type = 'application/json') {
  return {redirects: 0, tags: {name}, timeout: '60s',
    headers: {'Content-Type': type, Origin: origin, Cookie: `__Host-qf_session=${cookie}`}};
}

function ok(response, name) {
  if (!check(response, {[`${name} 2xx`]: r => r.status >= 200 && r.status < 300})) {
    let code = 'UNKNOWN';
    try { const value = response.json('error.code'); if (/^[A-Z_]{1,64}$/.test(value)) code = value; } catch (_) { /* 非 JSON 响应 */ }
    fail(`${name} HTTP ${response.status} ${code}`);
  }
  return response.status === 204 ? null : response.json();
}

// 登录在加压之前串行完成：登录有独立的 scrypt 舱壁，不属于练习负载（M3 待办：登录失败直接中止）。
export function setup() {
  const cookies = [];
  for (const user of users.slice(0, vus + importUsers)) {
    const login = http.post(`${base}/api/auth/login`, JSON.stringify({email: user.email, password: user.password}),
      {redirects: 0, tags: {name: 'login'}, headers: {'Content-Type': 'application/json', Origin: origin}});
    if (login.status !== 200) exec.test.abort(`登录失败 HTTP ${login.status}：先确认应用已就绪、users.json 属于这个项目`);
    const cookie = login.cookies['__Host-qf_session']?.[0]?.value;
    if (!cookie) exec.test.abort('登录响应没有会话 Cookie');
    cookies.push(cookie);
  }
  return {cookies};
}

// 每个 VU 固定一个用户（按全局 VU 编号，两个场景的 VU 编号不重叠，所以不会两个 VU 写同一个用户）。
function account(data) {
  const index = exec.vu.idInTest - 1;
  if (index >= data.cookies.length) fail('VU 数超过已登录的用户数');
  return {user: users[index], cookie: data.cookies[index], index};
}

// 偏向 300 题题库（M3 第二轮：小题库会掩盖整本题库级别的开销）。
export function practice(data) {
  const {user, cookie} = account(data);
  const big = user.collections.find(c => c.sample === 'loadtest-300.qbank');
  if (!big) fail('缺少 loadtest-300 题库：用 seed.py 重新造数');
  const bank = Math.random() < 0.8 ? big : user.collections[Math.floor(Math.random() * user.collections.length)];
  const iteration = exec.vu.iterationInScenario;
  const qid = bank.questionIds[iteration % bank.questionIds.length];
  if (iteration >= bank.questionIds.length && bank === big) fail('题目用完：每次复测都用全新的用户');
  const path = `${base}/api/collections/bank/${bank.id}`;
  ok(http.get(path, params(cookie, 'overview')), 'overview');
  const question = ok(http.get(`${path}/questions/${qid}`, params(cookie, 'read')), 'read');
  sleep(3 + Math.random() * 5);
  // 已提交的题只读不写（否则是大量正常的 409，污染错误率）。
  if (question.state?.status === 'submitted') return;
  let revision = question.state?.revision ?? 0;
  for (let i = 0; i < 2; i++) {
    const draft = ok(http.post(`${path}/questions/${qid}/actions`, JSON.stringify({
      requestId: `cap-${__VU}-${iteration}-d${i}-${Date.now()}`, revision, action: 'draft', data: {answer: bank.answers[qid]}}),
      params(cookie, 'draft')), 'draft');
    revision = draft.revision;
    sleep(1 + Math.random() * 2);
  }
  const started = Date.now();
  let submitted = ok(http.post(`${path}/questions/${qid}/actions`, JSON.stringify({
    requestId: `cap-${__VU}-${iteration}-s-${Date.now()}`, revision, action: 'submit', data: {answer: bank.answers[qid]}}),
    params(cookie, 'submit')), 'submit');
  if (submitted.browserGrading) {
    // 浏览器判分（ADR-011）：k6 没有 Worker，用仓库内合成样例的适配器计算结果后上报。
    const ticket = submitted.browserGrading;
    submitted = ok(http.post(`${path}/grading/reports`, JSON.stringify({items: [{
      questionId: qid, generation: ticket.generation, outcome: gradeFixture(ticket)}]}), params(cookie, 'grading-report')), 'grading-report').items[0];
  }
  submitFlow.add(Date.now() - started);
  check(submitted, {'已评分或等待人工': r => ['graded', 'pending'].includes(r.state?.result?.gradingStatus) || r.state?.status === 'submitted'});
  ok(http.get(`${path}/summary`, params(cookie, 'summary')), 'summary');
  sleep(1 + Math.random() * 2);
}

export function imports(data) {
  const {cookie, index: slot} = account(data);
  const started = Date.now();
  const accepted = ok(http.post(`${base}/api/library/uploads?filename=capacity-${slot}-${exec.vu.iterationInScenario}.qbank`, archive,
    params(cookie, 'upload', 'application/octet-stream')), 'upload');
  const deadline = Date.now() + 180000;
  for (;;) {
    const detail = ok(http.get(`${base}/api/library/packs/${accepted.packId}`, params(cookie, 'import-status')), 'import-status');
    const version = detail.revisions.find(row => row.revision === accepted.revision);
    if (version?.status === 'rejected') { importsRejected.add(1); fail(`导入被拒绝：${version.reason}`); }
    if (version?.banks?.length) break;
    if (Date.now() > deadline) fail('等待导入校验超过 3 分钟');
    sleep(1);
  }
  importDuration.add(Date.now() - started);
  importsDone.add(1);
  sleep(Number(__ENV.IMPORT_PAUSE_SECONDS || 20));
}
