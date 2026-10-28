import http from 'node:http';
import {createHash, timingSafeEqual} from 'node:crypto';
import path from 'node:path';
import {WorkerPool} from './pool.js';
import {loadPackage} from './blobs.js';
import {failure, LIMITS, RunnerError} from './errors.js';
import {object} from './validation.js';

const operations = new Set(['capabilities', 'validateBank', 'outlineBatch', 'scoreBatch', 'project', 'projectBatch', 'validateAnswer', 'submit', 'review', 'prepareAiGrading']);
const background = new Set(['capabilities', 'validateBank', 'outlineBatch', 'scoreBatch', 'projectBatch']);
const hash = token => createHash('sha256').update(token).digest();
const invalid = () => { throw new RunnerError('INVALID_REQUEST', 'Invalid execution request'); };

function requestShape(value) {
  if (!object(value)) invalid();
  if (value.protocolVersion !== 1) throw new RunnerError('UNSUPPORTED_PROTOCOL', 'Unsupported protocol version');
  if (typeof value.requestId !== 'string' || !value.requestId.length || value.requestId.length > 128 || !operations.has(value.op) || !object(value.input)) invalid();
  const spec = value.package;
  if (!object(spec) || !/^[a-f0-9]{64}$/.test(spec.digest) || !object(spec.apiVersion)) invalid();
  for (const key of ['rules', 'questionSchema', 'answerSchema']) if (!object(spec[key]) || typeof spec[key].sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(spec[key].sha256)) invalid();
  for (const key of ['outlineItemsDeclared', 'answerStateDeclared']) if (spec[key] !== undefined && typeof spec[key] !== 'boolean') invalid();
  const deadlineMs = value.deadlineMs ?? 8000;
  if (!Number.isInteger(deadlineMs) || deadlineMs < 1 || deadlineMs > 2147483647) invalid();
  if (value.priority !== undefined && !['foreground', 'background'].includes(value.priority)) invalid();
  const required = {
    validateBank: ['questions'], outlineBatch: ['questions'], scoreBatch: ['questions'], projectBatch: ['questions'],
    project: ['data', 'state'], validateAnswer: ['data', 'answer'], submit: ['data', 'answer'],
    review: ['data', 'answer', 'review', 'state'], prepareAiGrading: ['data', 'answer', 'state'],
  }[value.op] ?? [];
  if (required.some(key => !Object.hasOwn(value.input, key))) invalid();
  return {...value, deadlineMs, package: {
    digest: spec.digest, apiVersion: spec.apiVersion,
    outlineItemsDeclared: spec.outlineItemsDeclared === true, answerStateDeclared: spec.answerStateDeclared === true,
    rules: spec.rules, questionSchema: spec.questionSchema, answerSchema: spec.answerSchema,
  }};
}

async function readRequest(req) {
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] ?? '')) invalid();
  if (Number(req.headers['content-length']) > LIMITS.input) throw new RunnerError('RULE_INPUT_TOO_LARGE', 'Rule input exceeds size limit');
  const bytes = await new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    const cleanup = () => { req.off('data', data); req.off('end', end); req.off('error', error); req.off('aborted', aborted); };
    const error = () => { cleanup(); reject(new RunnerError('INVALID_REQUEST', 'Incomplete request body')); };
    const aborted = error;
    const end = () => { cleanup(); resolve(Buffer.concat(chunks)); };
    const data = chunk => {
      size += chunk.length;
      if (size > LIMITS.input) { cleanup(); req.resume(); reject(new RunnerError('RULE_INPUT_TOO_LARGE', 'Rule input exceeds size limit')); }
      else chunks.push(chunk);
    };
    req.on('data', data); req.once('end', end); req.once('error', error); req.once('aborted', aborted);
  });
  try { return requestShape(JSON.parse(bytes.toString('utf8'))); }
  catch (error) { if (error instanceof RunnerError) throw error; invalid(); }
}

export function createRunner({token, blobDir, poolOptions, logger = record => console.log(JSON.stringify(record))} = {}) {
  if (typeof token !== 'string' || !token.length || !blobDir) throw new Error('QF_RULE_RUNNER_TOKEN and QF_BLOB_DIR are required');
  const expectedToken = hash(token);
  const pool = new WorkerPool(poolOptions);
  const metrics = {operations: {}, timeouts: 0};
  let admitted = 0;
  const server = http.createServer(async (req, res) => {
    const start = Date.now();
    let request;
    let accepted = false;
    const reply = (status, body) => {
      let encoded = JSON.stringify(body);
      if (Buffer.byteLength(encoded) > LIMITS.output) encoded = JSON.stringify(failure('RULE_OUTPUT_LIMIT', 'Rule output exceeds size limit'));
      if (!res.destroyed) { res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store'}); res.end(encoded); }
    };
    try {
      if (!timingSafeEqual(expectedToken, hash(String(req.headers['x-internal-token'] ?? '')))) {
        req.resume(); reply(401, failure('UNAUTHENTICATED', 'Invalid internal token')); return;
      }
      if (req.method === 'GET' && req.url === '/v1/health') { reply(200, {ok: true, protocolVersion: 1}); return; }
      if (req.method === 'GET' && req.url === '/v1/metrics') {
        reply(200, {...metrics, workers: pool.workers.size, queueLength: pool.queue.length}); return;
      }
      if (req.method !== 'POST' || req.url !== '/v1/execute') { req.resume(); reply(404, failure('NOT_FOUND', 'Endpoint not found')); return; }
      // Bound in-flight body/file loading as well as the worker waiting queue.
      if (admitted >= pool.limit + pool.queueLimit) throw new RunnerError('OVERLOADED', 'Rule runner waiting queue is full', 503);
      admitted++; accepted = true;
      request = await readRequest(req);
      const deadline = start + request.deadlineMs;
      let result;
      if (deadline <= Date.now()) result = failure('RULE_TIMEOUT', 'Rule request exceeded its deadline');
      else {
        let timer;
        let files;
        try {
          files = await Promise.race([
            loadPackage(path.resolve(blobDir), request.package),
            new Promise((_, reject) => { timer = setTimeout(() => reject(new RunnerError('RULE_TIMEOUT', 'Rule request exceeded its deadline')), deadline - Date.now()); }),
          ]);
        } finally { clearTimeout(timer); }
        result = await pool.execute(request.package, files, request.op, request.input, deadline,
          request.priority ? request.priority === 'foreground' : !background.has(request.op), start);
      }
      const elapsedMs = Date.now() - start;
      record(result.code ?? 'OK', elapsedMs);
      reply(200, result);
    } catch (error) {
      const safe = error instanceof RunnerError ? error : new RunnerError('INTERNAL_ERROR', 'Rule runner internal failure', 500);
      record(safe.code, Date.now() - start);
      req.resume(); reply(safe.status, failure(safe.code, safe.message));
    } finally { if (accepted) admitted--; }
    function record(code, elapsedMs) {
      const op = request?.op ?? 'invalid';
      const value = metrics.operations[op] ??= {count: 0, sumMs: 0, maxMs: 0, buckets: {'10': 0, '50': 0, '100': 0, '500': 0, '1000': 0, '8000': 0, '+Inf': 0}};
      value.count++; value.sumMs += elapsedMs; value.maxMs = Math.max(value.maxMs, elapsedMs);
      for (const bound of Object.keys(value.buckets)) if (bound === '+Inf' || elapsedMs <= Number(bound)) value.buckets[bound]++;
      if (code === 'RULE_TIMEOUT') metrics.timeouts++;
      try { logger({requestId: request?.requestId ?? null, op, digest: request?.package.digest.slice(0, 12) ?? null, elapsedMs, code}); } catch { /* Logging must not affect rule results. */ }
    }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.maxRequestsPerSocket = 1000;
  server.on('close', () => pool.close());
  return {server, pool, close: async () => {
    pool.close(); server.closeAllConnections();
    if (server.listening) await new Promise(resolve => server.close(resolve));
  }};
}
