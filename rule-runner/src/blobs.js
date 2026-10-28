import {open, lstat, realpath} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import path from 'node:path';
import {LIMITS, RunnerError} from './errors.js';

export function blobPath(root, hash) {
  if (!/^[a-f0-9]{64}$/.test(hash)) throw new RunnerError('INVALID_REQUEST', 'Invalid SHA-256 reference');
  return path.join(root, 'sha256', hash.slice(0, 2), hash.slice(2, 4), hash);
}

// Exact regular files only: the permission model follows symlinks, so reject them here.
export async function readBlob(root, hash) {
  const file = blobPath(root, hash);
  let handle;
  try {
    const resolvedRoot = await realpath(root);
    let current = root;
    for (const part of path.relative(root, file).split(path.sep)) {
      current = path.join(current, part);
      if ((await lstat(current)).isSymbolicLink()) throw new Error('symlink');
    }
    if (await realpath(file) !== path.join(resolvedRoot, path.relative(root, file))) throw new Error('path');
    handle = await open(file, 'r');
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > LIMITS.asset) throw new RunnerError('RULE_ASSET_TOO_LARGE', 'Rule asset exceeds size limit');
    const buffer = Buffer.alloc(LIMITS.asset + 1);
    let length = 0;
    while (length < buffer.length) {
      const {bytesRead} = await handle.read(buffer, length, buffer.length - length, null);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length > LIMITS.asset) throw new RunnerError('RULE_ASSET_TOO_LARGE', 'Rule asset exceeds size limit');
    const bytes = buffer.subarray(0, length);
    if (createHash('sha256').update(bytes).digest('hex') !== hash) throw new RunnerError('HASH_MISMATCH', 'Rule asset hash mismatch');
    return file;
  } catch (error) {
    if (error instanceof RunnerError) throw error;
    throw new RunnerError('RULE_ASSET_UNAVAILABLE', 'Rule asset is unavailable or unsafe');
  } finally {
    await handle?.close();
  }
}

export async function loadPackage(root, spec) {
  const files = {};
  for (const field of ['rules', 'questionSchema', 'answerSchema']) files[field] = await readBlob(root, spec[field].sha256);
  return files;
}
