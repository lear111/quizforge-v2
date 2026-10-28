import {mkdtemp, mkdir, writeFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {createRunner} from '../src/server.js';
import {blobPath} from '../src/blobs.js';

export const fixtures = fileURLToPath(new URL('./fixtures/', import.meta.url));
export const basicRule = `QF.defineType({project(d){return d},grade(){return {score:1,maxScore:1,correct:true,feedback:null}},getScore(){return {score:1,maxScore:1}}});`;
export const token = 'rule-runner-integration-test-token';
export const digest = text => createHash('sha256').update(text).digest('hex');

export async function harness(t, poolOptions = {}) {
  const dir = await mkdtemp(path.join(tmpdir(), 'quizforge-rules-'));
  const logs = [];
  const app = createRunner({token, blobDir: dir, poolOptions: {limit: 2, ...poolOptions}, logger: record => logs.push(record)});
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await app.close(); await rm(dir, {recursive: true, force: true}); });
  const url = `http://127.0.0.1:${app.server.address().port}`;
  async function blob(bytes) {
    const sha256 = digest(bytes), file = blobPath(dir, sha256);
    await mkdir(path.dirname(file), {recursive: true}); await writeFile(file, bytes);
    return {sha256};
  }
  async function pack(source = basicRule, question = true, answer = true, overrides = {}) {
    return {digest: digest(source), apiVersion: {major: 1, minor: 5}, outlineItemsDeclared: true, answerStateDeclared: false,
      rules: await blob(source), questionSchema: await blob(JSON.stringify(question)), answerSchema: await blob(JSON.stringify(answer)), ...overrides};
  }
  function request(spec, op = 'project', input = {data: {hello: 'world'}, state: {submitted: false, result: null}}, extra = {}) {
    return {protocolVersion: 1, requestId: 'test-request', op, deadlineMs: 8000, package: spec, input, ...extra};
  }
  async function post(body, headers = {}) {
    const response = await fetch(url + '/v1/execute', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Internal-Token': token, ...headers}, body: JSON.stringify(body)});
    return {status: response.status, body: await response.json()};
  }
  async function get(endpoint, headers = {}) {
    const response = await fetch(url + endpoint, {headers: {'X-Internal-Token': token, ...headers}});
    return {status: response.status, body: await response.json()};
  }
  return {...app, dir, logs, blob, pack, request, post, get, url};
}

export async function legacy(spec, dir, op, input) {
  const payload = {op, apiVersion: spec.apiVersion, outlineItemsDeclared: spec.outlineItemsDeclared, answerStateDeclared: spec.answerStateDeclared, ...input};
  for (const field of ['rules', 'questionSchema', 'answerSchema']) payload[field] = blobPath(dir, spec[field].sha256);
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('./reference/rules-runner.cjs', import.meta.url))], {stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true});
    let output = '', errors = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { errors += chunk; });
    child.on('error', reject);
    child.on('close', () => { try { resolve(JSON.parse(output)); } catch { reject(new Error(errors || output)); } });
    child.stdin.end(JSON.stringify(payload));
  });
}

export async function loadFixture(h, name) {
  const base = path.join(fixtures, name);
  const manifest = JSON.parse(await readFile(path.join(base, 'manifest.json'), 'utf8'));
  const bank = JSON.parse(await readFile(path.join(base, manifest.examples), 'utf8'));
  const source = await readFile(path.join(base, manifest.rules), 'utf8');
  return {bank, spec: await h.pack(source, JSON.parse(await readFile(path.join(base, manifest.questionSchema))), JSON.parse(await readFile(path.join(base, manifest.answerSchema))), {
    outlineItemsDeclared: manifest.requiresApi.capabilities.includes('outline-items'), answerStateDeclared: manifest.requiresApi.capabilities.includes('answer-state'),
  })};
}
