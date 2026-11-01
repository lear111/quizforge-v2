// Opt-in measurement-chain check. Consumes drafts/submissions in fresh local fixtures.
import http from 'k6/http';
import {check, fail, sleep} from 'k6';

const users = JSON.parse(open(__ENV.USERS_FILE || '/work/users.json'));
const base = __ENV.BASE_URL || 'http://nginx';
if (!/^http:\/\/(nginx|localhost|127\.0\.0\.1)(:\d+)?$/.test(base)) throw Error('Only local targets are allowed');
const vus = Number(__ENV.VUS || 20);
if (!Number.isInteger(vus) || vus < 1 || users.length < vus) throw Error('Seed at least one fresh user per VU');
export const options = {
  vus, duration: __ENV.DURATION || '60s', gracefulStop: '15s', setupTimeout: '120s',
  thresholds: {checks: ['rate==1'], http_req_failed: ['rate==0']},
};
let cookie;
function params(name) {
  return {redirects: 0, headers: {
    'Content-Type': 'application/json', Origin: __ENV.ORIGIN || 'http://localhost:8088',
    ...(cookie ? {Cookie: `__Host-qf_session=${cookie}`} : {}),
  }, tags: {name}};
}
function requireOk(response, name) {
  if (!check(response, {[`${name} 200`]: r => r.status === 200})) {
    let code = 'UNKNOWN';
    try { const value = response.json('error.code'); if (/^[A-Z_]{1,64}$/.test(value)) code = value; } catch (_) { /* Non-JSON failure. */ }
    fail(`${name} HTTP ${response.status} ${code}`);
  }
  return response.json();
}
// Login sequentially outside the VU workload: this check targets app read/write CPU,
// rather than the separate two-permit scrypt bulkhead.
export function setup() {
  const cookies = [];
  for (const user of users.slice(0, vus)) {
    const login = http.post(`${base}/api/auth/login`, JSON.stringify({email: user.email, password: user.password}), params('login'));
    requireOk(login, 'login');
    const token = login.cookies['__Host-qf_session']?.[0]?.value;
    if (!token) fail('Missing session cookie');
    cookies.push(token);
  }
  return {cookies};
}
export default function (data) {
  const user = users[__VU - 1];
  cookie = data.cookies[__VU - 1];
  const bank = user.collections.find(c => c.sample === 'loadtest-300.qbank');
  if (!bank) fail('Missing loadtest-300 fixture');
  // One VU per user, distinct question per iteration. Fail instead of wrapping into consumed state.
  if (__ITER >= bank.questionIds.length) fail('Fixtures exhausted; seed fresh users for each recording');
  const qid = bank.questionIds[__ITER];
  const path = `${base}/api/collections/bank/${bank.id}`;
  requireOk(http.get(path, params('overview')), 'overview');
  const question = requireOk(http.get(`${path}/questions/${qid}`, params('read')), 'read');
  if (question.state.status === 'submitted') fail('Use fresh, unsubmitted fixtures');
  const payload = {requestId: `profile-${__VU}-${__ITER}-draft`, revision: question.state.revision,
    action: 'draft', data: {answer: bank.answers[qid]}};
  const draft = requireOk(http.post(`${path}/questions/${qid}/actions`, JSON.stringify(payload), params('draft')), 'draft');
  payload.requestId = `profile-${__VU}-${__ITER}-submit`;
  payload.revision = draft.revision;
  payload.action = 'submit';
  const submitted = requireOk(http.post(`${path}/questions/${qid}/actions`, JSON.stringify(payload), params('submit')), 'submit');
  check(submitted, {'objective full score': r => r.state.result.score === r.state.result.maxScore});
  sleep(0.2);
}
