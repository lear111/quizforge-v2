import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile, writeFile} from 'node:fs/promises';
import {harness} from './helpers.js';
import {blobPath} from '../src/blobs.js';

test('fixture blobs await concurrent writes and never rewrite an existing shared hash', async t => {
  const h = await harness(t);
  const refs = await Promise.all(Array.from({length: 20}, () => h.blob('shared schema')));
  const file = blobPath(h.dir, refs[0].sha256);
  assert.ok(refs.every(ref => ref.sha256 === refs[0].sha256));
  assert.equal(await readFile(file, 'utf8'), 'shared schema');
  // Deliberate corruption remains under the caller's control; fixture creation cannot repair/rewrite it.
  await writeFile(file, 'corrupted');
  await h.blob('shared schema');
  assert.equal(await readFile(file, 'utf8'), 'corrupted');
});
