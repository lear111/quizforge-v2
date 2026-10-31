import http from 'k6/http';
import {check, fail} from 'k6';
const users=JSON.parse(open('./users.json'));
const base=__ENV.BASE_URL || 'http://nginx';
if (!/^http:\/\/(nginx|localhost|127\.0\.0\.1)(:\d+)?$/.test(base)) throw Error('Only local Compose nginx or loopback is allowed');
export const options={vus:1,iterations:1,thresholds:{checks:['rate==1'],http_req_failed:['rate==0']}};
export default function(){
  const user=users[0];
  const login=http.post(`${base}/api/auth/login`,JSON.stringify({email:user.email,password:user.password}),
    {redirects:0,headers:{'Content-Type':'application/json',Origin:__ENV.ORIGIN || 'http://localhost:8088'},tags:{name:'login'}});
  if (!check(login,{'login 200':r=>r.status===200})) fail('Login failed');
  // Local HTTP only: explicitly replay the production Secure cookie in this test client.
  const cookie=login.cookies['__Host-qf_session']?.[0]?.value;
  if(!cookie) fail('Missing session cookie');
  const bank=user.collections[0];
  const response=http.get(`${base}/api/collections/bank/${bank.id}/questions/${bank.questionIds[0]}`,
    {redirects:0,headers:{Cookie:`__Host-qf_session=${cookie}`},tags:{name:'read-question'}});
  check(response,{'read 200':r=>r.status===200,'projected question returned':r=>!!r.json('question.data')});
}
