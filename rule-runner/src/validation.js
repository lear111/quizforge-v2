import {RunnerError} from './errors.js';

export function reject(message = 'Extension rules rejected or could not process this data', code = 'RULE_REJECTED') {
  throw new RunnerError(code, message);
}
export const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function apiVersion(version = {major: 1, minor: 0}) {
  if (!object(version) || !Number.isInteger(version.major) || version.major < 1 || version.major > 2147483647
      || !Number.isInteger(version.minor) || version.minor < 0 || version.minor > 2147483647) reject('Invalid rule API version', 'INVALID_API_VERSION');
  if (version.major !== 1 || version.minor > 5) reject('Unsupported rule API version', 'UNSUPPORTED_API_VERSION');
  return version;
}

export function gradeResult(result, allowPending = true) {
  const status = result?.gradingStatus ?? 'graded';
  if (!object(result) || (Object.hasOwn(result, 'gradingStatus') && typeof result.gradingStatus !== 'string')
      || !['pending', 'graded'].includes(status) || !Number.isFinite(result.maxScore) || result.maxScore < 0 || !Object.hasOwn(result, 'feedback')) reject();
  if (status === 'pending') {
    if (!allowPending || result.score !== null || result.correct !== null) reject();
  } else if (!Number.isFinite(result.score) || typeof result.correct !== 'boolean') reject();
  return status;
}

export function outlineStates(items, submitted) {
  const invalid = () => reject('Invalid outline states', 'INVALID_OUTLINE_STATES');
  if (!Array.isArray(items) || items.length > 100) invalid();
  const ids = new Set();
  for (const item of items) {
    if (!object(item) || Object.keys(item).length !== 2 || !Object.hasOwn(item, 'id') || !Object.hasOwn(item, 'status')
        || !plain(item.id, 128) || ids.has(item.id) || !['unanswered', 'draft', 'correct', 'incorrect'].includes(item.status)
        || (!submitted && !['unanswered', 'draft'].includes(item.status)) || (submitted && item.status === 'draft')) invalid();
    ids.add(item.id);
  }
  return items;
}
const plain = (text, limit) => typeof text === 'string' && text.length > 0 && text.length <= limit && text.trim() === text && !/[\u0000-\u001f\u007f-\u009f<>]/u.test(text);

export function outlineItems(items) {
  const invalid = () => reject('Invalid outline items', 'INVALID_OUTLINE_ITEMS');
  if (!Array.isArray(items) || items.length > 100) invalid();
  const ids = new Set();
  for (const item of items) {
    if (!object(item) || Object.keys(item).length !== 2 || !plain(item.id, 128) || !plain(item.label, 80) || ids.has(item.id)) invalid();
    ids.add(item.id);
  }
  return items;
}

export function scoreValue(score, gradingStatus, submitted, answerStateDeclared) {
  if (!object(score) || !Number.isFinite(score.maxScore) || score.maxScore < 0
      || (gradingStatus === 'pending' ? score.score !== null : !Number.isFinite(score.score))) reject('Score is unavailable', 'SCORE_UNAVAILABLE');
  const value = {score: score.score, maxScore: score.maxScore, gradingStatus};
  if (Object.hasOwn(score, 'outlineStates') && (submitted || !answerStateDeclared)) value.outlineStates = outlineStates(score.outlineStates, submitted);
  return value;
}

export function list(items, code = 'RULE_REJECTED') {
  if (!Array.isArray(items) || items.length > 10000) reject('Invalid question list', code);
}
export function boundaries(offsets, length) {
  if (!Array.isArray(offsets) || offsets.length > length || offsets.some((value, index) => !Number.isInteger(value)
      || value <= 0 || value >= length || (index > 0 && value <= offsets[index - 1]))) reject();
}
