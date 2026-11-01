// Fixed labels only: never export package digests, request IDs, file paths or rule data.
export const OPERATIONS = Object.freeze(['capabilities', 'validateBank', 'outlineBatch', 'scoreBatch', 'project',
  'projectBatch', 'validateAnswer', 'submit', 'review', 'prepareAiGrading']);
export const STOP_REASONS = Object.freeze(['lru', 'max_requests', 'timeout', 'abnormal_exit', 'idle', 'shutdown', 'output_limit']);
const bounds = [0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120];

class Histogram {
  constructor() {
    this.values = new Map([...OPERATIONS, 'other'].map(op => [op, {count: 0, sum: 0, buckets: bounds.map(() => 0)}]));
  }
  observe(op, seconds) {
    const value = this.values.get(OPERATIONS.includes(op) ? op : 'other');
    value.count++; value.sum += seconds;
    bounds.forEach((bound, index) => { if (seconds <= bound) value.buckets[index]++; });
  }
  render(name, help) {
    const lines = [`# HELP ${name} ${help}`, `# TYPE ${name} histogram`];
    for (const [op, value] of this.values) {
      bounds.forEach((bound, index) => lines.push(`${name}_bucket{op="${op}",le="${bound}"} ${value.buckets[index]}`));
      lines.push(`${name}_bucket{op="${op}",le="+Inf"} ${value.count}`,
        `${name}_sum{op="${op}"} ${value.sum}`, `${name}_count{op="${op}"} ${value.count}`);
    }
    return lines;
  }
}

export class PoolMetrics {
  constructor(now = () => performance.now()) {
    this.now = now;
    this.queueWait = new Histogram();
    this.execution = new Histogram();
    this.starts = 0;
    this.stops = Object.fromEntries(STOP_REASONS.map(reason => [reason, 0]));
  }
  queued(job) { job.queuedAt = this.now(); }
  assigned(job) {
    this.queueWait.observe(job.payload.op, Math.max(0, this.now() - job.queuedAt) / 1000);
  }
  dispatched(job) { job.dispatchedAt = this.now(); }
  finished(job, endedAt = this.now()) {
    if (job.dispatchedAt === undefined) return;
    this.execution.observe(job.payload.op, Math.max(0, endedAt - job.dispatchedAt) / 1000);
    job.dispatchedAt = undefined; // Receiving and retiring the same job must not count it twice.
  }
  started() { this.starts++; }
  stopped(reason) { this.stops[Object.hasOwn(this.stops, reason) ? reason : 'abnormal_exit']++; }
  render(pool) {
    return [
      ...this.queueWait.render('qf_runner_queue_wait_seconds', 'Time from queue entry to worker assignment; dispatched requests only.'),
      ...this.execution.render('qf_runner_execution_seconds', 'Time from worker stdin write to a complete result frame or active termination, including IPC and cold startup.'),
      '# HELP qf_runner_worker_starts_total Worker process spawn attempts.',
      '# TYPE qf_runner_worker_starts_total counter', `qf_runner_worker_starts_total ${this.starts}`,
      '# HELP qf_runner_worker_stops_total Worker retirements by reason, counted once per registered worker.',
      '# TYPE qf_runner_worker_stops_total counter',
      ...Object.entries(this.stops).map(([reason, count]) => `qf_runner_worker_stops_total{reason="${reason}"} ${count}`),
      '# HELP qf_runner_workers Workers currently registered in the pool, busy or idle.',
      '# TYPE qf_runner_workers gauge', `qf_runner_workers ${pool.workers.size}`,
      '# HELP qf_runner_queue_length Requests currently waiting for worker assignment.',
      '# TYPE qf_runner_queue_length gauge', `qf_runner_queue_length ${pool.queue.length}`, '',
    ].join('\n');
  }
}
