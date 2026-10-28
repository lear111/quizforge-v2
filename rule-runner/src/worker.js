import {openSync, closeSync, fstatSync, readSync, lstatSync, realpathSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {Operations} from './operations.js';
import {failure, LIMITS, RunnerError} from './errors.js';

function asset(file, expected) {
  // Recheck inside the worker too: a pooled process must never trust a prior read.
  if (!lstatSync(file).isFile() || realpathSync(file) !== file) throw new RunnerError('RULE_ASSET_UNAVAILABLE', 'Rule asset is unsafe');
  const fd = openSync(file, 'r');
  let bytes;
  try {
    if (fstatSync(fd).size > LIMITS.asset) throw new RunnerError('RULE_ASSET_TOO_LARGE', 'Rule asset exceeds size limit');
    const buffer = Buffer.alloc(LIMITS.asset + 1);
    let length = 0;
    while (length < buffer.length) {
      const count = readSync(fd, buffer, length, buffer.length - length, null);
      if (!count) break;
      length += count;
    }
    if (length > LIMITS.asset) throw new RunnerError('RULE_ASSET_TOO_LARGE', 'Rule asset exceeds size limit');
    bytes = buffer.subarray(0, length);
  } finally { closeSync(fd); }
  if (createHash('sha256').update(bytes).digest('hex') !== expected) throw new RunnerError('HASH_MISMATCH', 'Rule asset hash mismatch');
  return bytes.toString('utf8');
}

function execute(job) {
  try {
    const read = name => asset(job.files[name], job.spec[name].sha256);
    const operations = new Operations(read('rules'), JSON.parse(read('questionSchema')), JSON.parse(read('answerSchema')), job.spec);
    const data = operations[job.op](job.input);
    const text = JSON.stringify({ok: true, data});
    if (Buffer.byteLength(text) > LIMITS.output) return {...failure('RULE_OUTPUT_LIMIT', 'Rule output exceeds size limit'), retire: true};
    return {ok: true, data};
  } catch (error) {
    // Never echo errors originating in extension scripts or AJV: they may contain paths or secrets.
    if (error instanceof RunnerError) return {...failure(error.code, error.message), ...(error.code === 'RULE_TIMEOUT' ? {retire: true} : {})};
    return failure();
  }
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buffer += chunk;
  let newline;
  while ((newline = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1);
    let response;
    try { response = execute(JSON.parse(line)); } catch { response = failure(); }
    process.stdout.write(JSON.stringify(response) + '\n');
  }
  if (Buffer.byteLength(buffer) > LIMITS.input + 16384) process.exit(2);
});
process.stdin.on('end', () => process.exit(0));
