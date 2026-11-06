import {test} from 'node:test';
import assert from 'node:assert/strict';
import {deviceCaption} from '../public/devices.js';

test('the device caption names the client type and both times',()=>{
  const caption=deviceCaption({client:'android',created:'2026-11-05T01:02:03.000Z',lastUsed:'2026-11-06T01:02:03.000Z'});
  assert.match(caption,/^Android · 连接于 .+ · 最后使用 .+$/);
  assert.match(deviceCaption({client:'web',created:'2026-11-05T01:02:03.000Z',lastUsed:'2026-11-05T01:02:03.000Z'}),/^电脑 · /);
});
