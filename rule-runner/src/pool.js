import {spawn} from 'node:child_process';
import {availableParallelism} from 'node:os';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {failure, LIMITS, RunnerError} from './errors.js';

const root = fileURLToPath(new URL('../', import.meta.url));

export class WorkerPool {
  constructor({limit = availableParallelism() * 2, queueLimit = 200, idleMs = 60000, maxRequests = 500} = {}) {
    if (![limit, queueLimit, idleMs, maxRequests].every(value => Number.isSafeInteger(value) && value > 0)) throw new Error('Invalid pool configuration');
    Object.assign(this, {limit, queueLimit, idleMs, maxRequests});
    this.workers = new Set();
    this.queue = [];
    this.closed = false;
  }
  execute(spec, files, op, input, deadline, priority, startedAt = Date.now()) {
    if (this.closed) return Promise.reject(new RunnerError('UNAVAILABLE', 'Rule runner is shutting down', 503));
    return new Promise((resolve, reject) => {
      const job = {key: JSON.stringify(spec), payload: {spec, files, op, input}, deadline, priority, startedAt, resolve, reject};
      job.timer = setTimeout(() => {
        const index = this.queue.indexOf(job);
        if (index >= 0) this.queue.splice(index, 1);
        const worker = [...this.workers].find(worker => worker.job === job);
        if (worker) this.stop(worker, failure('RULE_TIMEOUT', 'Rule request exceeded its deadline'));
        else resolve(failure('RULE_TIMEOUT', 'Rule request exceeded its deadline'));
        this.dispatch();
      }, Math.max(1, deadline - Date.now()));
      this.queue.push(job);
      this.dispatch();
      // Jobs assigned immediately do not occupy a waiting-queue slot.
      if (this.queue.length > this.queueLimit) {
        this.queue.splice(this.queue.indexOf(job), 1);
        clearTimeout(job.timer);
        reject(new RunnerError('OVERLOADED', 'Rule runner waiting queue is full', 503));
      }
    });
  }
  dispatch() {
    if (this.closed) return;
    this.queue.sort((a, b) => Number(b.priority) - Number(a.priority));
    for (let index = 0; index < this.queue.length;) {
      const job = this.queue[index];
      let worker = [...this.workers].find(worker => worker.key === job.key && !worker.job);
      if (!worker && [...this.workers].filter(worker => worker.key === job.key).length < 2) {
        if (this.workers.size >= this.limit) {
          const idle = [...this.workers].filter(worker => !worker.job).sort((a, b) => a.lastUsed - b.lastUsed)[0];
          if (idle) this.stop(idle);
        }
        if (this.workers.size < this.limit) worker = this.start(job);
      }
      if (!worker) { index++; continue; }
      this.queue.splice(index, 1);
      if (job.deadline <= Date.now()) {
        clearTimeout(job.timer); job.resolve(failure('RULE_TIMEOUT', 'Rule request exceeded its deadline')); continue;
      }
      clearTimeout(worker.idle);
      worker.job = job; worker.buffer = Buffer.alloc(0); worker.stderrBytes = 0;
      worker.child.stdin.write(JSON.stringify(job.payload) + '\n');
    }
  }
  start(job) {
    const args = ['--permission', '--max-old-space-size=96', '--disable-proto=throw'];
    // Trusted service code/dependencies plus these three exact assets; never the blob tree.
    for (const allowed of [path.join(root, 'src'), path.join(root, 'node_modules'), path.join(root, 'package.json'), ...Object.values(job.payload.files)]) args.push('--allow-fs-read=' + allowed);
    args.push(path.join(root, 'src', 'worker.js'));
    const child = spawn(process.execPath, args, {env: {}, cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});
    const worker = {child, key: job.key, job: null, served: 0, lastUsed: Date.now(), buffer: Buffer.alloc(0), stderrBytes: 0};
    this.workers.add(worker);
    child.stdin.on('error', () => {});
    child.stdout.on('data', chunk => this.receive(worker, chunk));
    child.stderr.on('data', chunk => {
      worker.stderrBytes += chunk.length;
      if (worker.stderrBytes > 65536) { this.stop(worker, failure()); this.dispatch(); }
    });
    child.once('error', () => { this.stop(worker, null, new RunnerError('INTERNAL_ERROR', 'Unable to start rule worker', 503)); this.dispatch(); });
    child.once('close', () => { this.stop(worker, failure()); this.dispatch(); });
    return worker;
  }
  receive(worker, chunk) {
    if (!this.workers.has(worker)) return;
    if (!worker.job) { this.stop(worker); this.dispatch(); return; }
    if (worker.buffer.length + chunk.length > LIMITS.output + 1) {
      this.stop(worker, failure('RULE_OUTPUT_LIMIT', 'Rule output exceeds size limit')); this.dispatch(); return;
    }
    worker.buffer = Buffer.concat([worker.buffer, chunk]);
    const newline = worker.buffer.indexOf(10);
    if (newline < 0) return;
    let response;
    try {
      if (newline !== worker.buffer.length - 1) throw new Error('extra output');
      response = JSON.parse(worker.buffer.subarray(0, newline));
      if (typeof response.ok !== 'boolean') throw new Error('invalid reply');
    } catch { this.stop(worker, failure()); this.dispatch(); return; }
    let retire = response.retire === true;
    delete response.retire;
    const job = worker.job;
    if (response.ok) {
      response.elapsedMs = Math.max(0, Date.now() - job.startedAt);
      if (Buffer.byteLength(JSON.stringify(response)) > LIMITS.output) {
        response = failure('RULE_OUTPUT_LIMIT', 'Rule output exceeds size limit'); retire = true;
      }
    }
    worker.job = null; clearTimeout(job.timer); worker.served++; worker.lastUsed = Date.now();
    worker.buffer = Buffer.alloc(0);
    if (retire || worker.served >= this.maxRequests) this.stop(worker);
    else { worker.idle = setTimeout(() => { this.stop(worker); this.dispatch(); }, this.idleMs); worker.idle.unref(); }
    job.resolve(response);
    this.dispatch();
  }
  stop(worker, response = failure(), error) {
    if (!this.workers.delete(worker)) return;
    clearTimeout(worker.idle);
    worker.child.kill('SIGKILL');
    if (worker.job) {
      clearTimeout(worker.job.timer);
      if (error) worker.job.reject(error); else worker.job.resolve(response);
      worker.job = null;
    }
  }
  close() {
    this.closed = true;
    for (const job of this.queue.splice(0)) { clearTimeout(job.timer); job.reject(new RunnerError('UNAVAILABLE', 'Rule runner is shutting down', 503)); }
    for (const worker of this.workers) this.stop(worker, null, new RunnerError('UNAVAILABLE', 'Rule runner is shutting down', 503));
  }
}
