// Run from the repository root; requires the legacy quizforge_hub Git checkout.
import {execFileSync} from 'node:child_process';
import {mkdtemp, mkdir, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL, fileURLToPath} from 'node:url';

const legacyRepo = process.argv[2];
if (!legacyRepo) throw Error('Usage: node backend/src/test/vectors/generate-extension-digests.mjs <legacy-hub-repo>');
const commit = '83d6dfd';
const work = await mkdtemp(path.join(tmpdir(), 'qf-digest-oracle-'));
const target = fileURLToPath(new URL('../resources/content/', import.meta.url));
await mkdir(target, {recursive: true});
try {
  // Import the original module unchanged, along with its original two local dependencies.
  for (const file of ['cloud/extension-review.mjs', 'cloud/accounts.mjs', 'accounts.mjs']) {
    await mkdir(path.dirname(path.join(work, file)), {recursive: true});
    await writeFile(path.join(work, file), execFileSync('git', ['-C', legacyRepo, 'show', `${commit}:${file}`]));
  }
  const {extensionDigest} = await import(pathToFileURL(path.join(work, 'cloud/extension-review.mjs')));
  const cases = [];
  async function add(name, entries) {
    const directory = path.join(work, 'samples', String(cases.length));
    const files = [];
    for (const [relative, bytes] of entries) {
      const file = path.join(directory, relative);
      await mkdir(path.dirname(file), {recursive: true}); await writeFile(file, bytes);
      files.push({path: relative, base64: bytes.toString('base64')});
    }
    cases.push({name, files, expected: extensionDigest(directory)});
  }
  // Read tracked bytes, so autocrlf cannot silently change executable/schema hashes.
  const project = fileURLToPath(new URL('../../../../', import.meta.url));
  const fixturePrefix = 'rule-runner/test/fixtures/';
  const tracked = execFileSync('git', ['-C', project, 'ls-tree', '-rz', '--name-only', 'HEAD', fixturePrefix]).toString().split('\0').filter(Boolean);
  for (const name of ['单选题', '多选题', '判断题', '简答题', '简答题（答题区）']) {
    const prefix = fixturePrefix + name + '/';
    const entries = tracked.filter(file => file.startsWith(prefix)).map(file => [file.slice(prefix.length), execFileSync('git', ['-C', project, 'show', 'HEAD:' + file])]);
    await add('basic-' + name, entries);
  }
  const raw = value => Buffer.from(value);
  await add('unicode-numbers-order-bindings-defaults', [
    ['manifest.json', raw('{"examples":"examples/bank.json","name":"中文😀","numbers":[1.0,1e21,-0,1e-7,1e-6,9007199254740993,1e400,5e-324],"z":{"10":10,"2":2,"01":1,"4294967294":4,"4294967295":5,"__proto__":{"x":1}},"escaped":"\\b\\f\\n\\r\\t\\u0000\\u001f\\\"\\\\/","unicode":"中文😀\u2028\u2029","lone":"\\ud800\\udfff"}')],
    ['examples/bank.json', raw('{"extension":{"path":"extensions/中文/任意"},"nested":[{"extension":{"path":"x"}},{"extension":{"path":"x","version":2}},null],"numbers":[1.0,1e21,-0],"a":{"z":1,"a":2}}')],
    ['a/z.txt', raw('directory before sibling a.js')], ['a.js', raw('raw bytes\r\n')],
    ['😀.txt', raw('supplementary')], ['\ue000.txt', raw('BMP')], ['opaque.bin', Buffer.from([0, 255, 128, 1])],
  ]);
  await add('null-defaults-and-duplicate-properties', [
    ['manifest.json', raw('{"examples":"examples/bank.json","id":null,"version":null,"name":"a","name":"b","z":2,"z":1}')],
    ['examples/bank.json', raw('{"extension":{"path":null},"nested":{"extension":"keep"},"value":-0}')],
  ]);
  await add('non-null-falsy-id-and-version', [
    ['manifest.json', raw('{"examples":"examples/bank.json","id":"","version":false}')],
    ['examples/bank.json', raw('[{"extension":{"path":"x"}},true,1.0]')],
  ]);
  await add('node-utf8-replacement', [
    ['manifest.json', Buffer.concat([raw('{"examples":"examples/bank.json","name":"'), Buffer.from([0xed,0xa0,0x80,0xe2,0x82]), raw('"}')])],
    ['examples/bank.json', Buffer.concat([raw('{"text":"'), Buffer.from([0xe2,0x82,0x41,0xf0,0x80,0x80,0x80]), raw('"}')])],
  ]);
  await add('deep-json-with-binding', [
    ['manifest.json', raw('{"examples":"examples/bank.json"}')],
    ['examples/bank.json', raw('['.repeat(1200)+'{"extension":{"path":"x"}}'+']'.repeat(1200))],
  ]);
  const numbers = [];
  const known = [0, -0, 1, 1e21, 1e20, 1e23, 1e-6, 1e-7, Number.MIN_VALUE, Number.MAX_VALUE, 1000000000000000100, Infinity, -Infinity, NaN];
  function number(value) { const bytes=Buffer.alloc(8); bytes.writeDoubleBE(value); numbers.push({bits:bytes.toString('hex'), expected:JSON.stringify(value)}); }
  known.forEach(number);
  let bits = 0x123456789abcdef0n;
  for (let index=0; index<2048; index++) {
    bits ^= bits << 13n; bits = BigInt.asUintN(64, bits); bits ^= bits >> 7n; bits ^= bits << 17n; bits = BigInt.asUintN(64, bits);
    const bytes=Buffer.alloc(8); bytes.writeBigUInt64BE(bits); number(bytes.readDoubleBE());
  }
  await writeFile(path.join(target, 'extension-digest-vectors.json'), JSON.stringify({legacyCommit:commit, cases}, null, 2)+'\n');
  await writeFile(path.join(target, 'ecma-number-vectors.json'), JSON.stringify(numbers, null, 2)+'\n');
  console.log(`Generated ${cases.length} digest vectors and ${numbers.length} binary64 vectors with Node ${process.version}`);
} finally { await rm(work, {recursive:true, force:true}); }
