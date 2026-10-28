(function () {
  'use strict';
  const KIND = 'single';
  const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
  const id = v => typeof v === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(v);
  function validDoc(doc) {
    if (!object(doc) || doc.type !== 'doc' || !Array.isArray(doc.content)) return false;
    let count = 0, meaningful = false;
    function visit(node, depth) {
      if (!object(node) || ++count > 10000 || depth > 64) return false;
      if (node.type === 'text') {
        if (typeof node.text !== 'string' || !node.text.length) return false;
        if (node.text.trim()) meaningful = true;
      }
      if (node.type === 'image') {
        if (!/^[a-f0-9]{64}$/.test(node.attrs?.assetId || '')) return false;
        meaningful = true;
      }
      if (['inlineMath','blockMath'].includes(node.type)) {
        if (typeof node.attrs?.latex !== 'string' || !node.attrs.latex.trim()) return false;
        meaningful = true;
      }
      return !node.content || Array.isArray(node.content) && node.content.every(child => visit(child, depth + 1));
    }
    return visit(doc, 0) && meaningful;
  }
  function validAnswer(answer, data) {
    if (!object(answer)) return false;
    if (KIND === 'true-false') return Object.keys(answer).length === 1 && (answer.value === null || typeof answer.value === 'boolean');
    const ids = new Set(data.options.map(option => option.id));
    if (KIND === 'single') return Object.keys(answer).length === 1 && (answer.selectedOptionId === null || ids.has(answer.selectedOptionId));
    return Object.keys(answer).length === 1 && Array.isArray(answer.selectedOptionIds) && new Set(answer.selectedOptionIds).size === answer.selectedOptionIds.length && answer.selectedOptionIds.every(value => ids.has(value));
  }
  QF.defineType({
    validateQuestion(data) {
      if (!object(data) || data.formatVersion !== 1 || !validDoc(data.stem) || !validDoc(data.answerAndExplanation) || !Number.isFinite(data.maxScore) || data.maxScore <= 0 || data.maxScore > 100000) return false;
      if (KIND === 'true-false') return typeof data.correctAnswer === 'boolean';
      if (!Array.isArray(data.options) || data.options.length < 2 || data.options.length > 26 || !data.options.every(option => object(option) && id(option.id) && validDoc(option.content)) || new Set(data.options.map(option => option.id)).size !== data.options.length) return false;
      if (KIND === 'single') return data.options.some(option => option.id === data.correctOptionId);
      return Array.isArray(data.correctOptionIds) && data.correctOptionIds.length > 0 && new Set(data.correctOptionIds).size === data.correctOptionIds.length && data.correctOptionIds.every(value => data.options.some(option => option.id === value));
    },
    validateAnswer: validAnswer,
    project(data, state) {
      const result = {formatVersion:1,stem:data.stem,maxScore:data.maxScore};
      if (KIND !== 'true-false') result.options = data.options;
      if (state?.submitted) {
        result.answerAndExplanation = data.answerAndExplanation;
        if (KIND === 'single') result.correctOptionId = data.correctOptionId;
        else if (KIND === 'multiple') result.correctOptionIds = data.correctOptionIds;
        else result.correctAnswer = data.correctAnswer;
      }
      return result;
    },
    grade(data, answer) {
      if (!validAnswer(answer, data)) throw new Error('作答包含无效选项');
      const filled = KIND === 'single' ? answer.selectedOptionId !== null : KIND === 'multiple' ? answer.selectedOptionIds.length > 0 : answer.value !== null;
      if (!filled) return {gradingStatus:'graded',score:0,maxScore:data.maxScore,correct:false,feedback:'未作答'};
      const correct = KIND === 'single' ? answer.selectedOptionId === data.correctOptionId : KIND === 'true-false' ? answer.value === data.correctAnswer : answer.selectedOptionIds.length === data.correctOptionIds.length && answer.selectedOptionIds.every(value => data.correctOptionIds.includes(value));
      return {gradingStatus:'graded',score:correct ? data.maxScore : 0,maxScore:data.maxScore,correct,feedback:correct ? '回答正确' : '回答错误'};
    },
    getScore(data, state) {
      return {score:state?.submitted || state?.status === 'submitted' ? state.result.score : 0,maxScore:data.maxScore};
    }
  });
})();
