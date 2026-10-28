(function () {
  'use strict';
  const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
  const total = data => data.parts.reduce((sum, part) => sum + part.maxScore, 0);
  function document(doc) {
    if (!object(doc) || doc.type !== 'doc' || !Array.isArray(doc.content) || !doc.content.length) return false;
    let count = 0;
    function visit(node, depth) {
      if (!object(node) || ++count > 10000 || depth > 64) return false;
      if (node.type === 'text' && (typeof node.text !== 'string' || !node.text.length)) return false;
      if (node.type === 'image' && !/^[a-f0-9]{64}$/.test(node.attrs?.assetId || '')) return false;
      if (['inlineMath','blockMath'].includes(node.type) && (typeof node.attrs?.latex !== 'string' || !node.attrs.latex.trim())) return false;
      return !node.content || Array.isArray(node.content) && node.content.every(child => visit(child, depth + 1));
    }
    return visit(doc, 0);
  }
  function nonempty(doc) {
    return object(doc) && (doc.type === 'image' || ['inlineMath','blockMath'].includes(doc.type) && !!doc.attrs?.latex?.trim() || doc.type === 'text' && !!doc.text?.trim() || Array.isArray(doc.content) && doc.content.some(nonempty));
  }
  const height = part => part.answerHeight ?? 480;
  const areaId = part => 'response-' + part.id;
  function areaNonempty(area) {return !!(area && (area.text?.trim() || area.strokes?.length || area.images?.length));}
  function areaValid(area,part) {
    return object(area) && area.type === 'qf-answer-area' && area.formatVersion === 1 && area.id === areaId(part) && area.width === 760 && Number.isInteger(area.height) && area.height >= height(part) && area.height <= 3200 && Array.isArray(area.strokes) && (area.targetId === undefined || area.targetId === part.id);
  }
  function answerValid(answer, data) {
    if (!object(answer) || Object.keys(answer).length !== 1 || !object(answer.responses)) return false;
    const entries = Object.entries(answer.responses);
    if (!entries.every(([id,area]) => {const part = data.parts.find(part => part.id === id);return part && areaValid(area,part);})) return false;
    return entries.reduce((sum,[,area]) => sum + area.height,0) <= 12000 && entries.reduce((sum,[,area]) => sum + area.width * area.height,0) <= 8000000;
  }
  function answerBlocks(area) {
    if (area.strokes?.length || area.images?.length) {
      const assetId = area.snapshot?.attrs?.assetId;
      if (!/^[a-f0-9]{64}$/.test(assetId || '')) throw new Error('答案图片尚未生成，请先提交答案');
      return [{type:'image',assetId}];
    }
    return area.text?.trim() ? [{type:'text',text:area.text.trim()}] : [];
  }
  function result(data, parts) {
    const score = data.parts.reduce((sum, part) => sum + parts[part.id].score, 0), maxScore = total(data);
    return {gradingStatus:'graded',score,maxScore,correct:score === maxScore,feedback:null,parts};
  }
  function blocks(doc) {
    const output = []; let text = '';
    function flush() {if (text.trim()) output.push({type:'text',text:text.trim()}); text = '';}
    function walk(node) {
      if (!node) return;
      if (node.type === 'image') {flush();output.push({type:'image',assetId:node.attrs.assetId,...(node.attrs.alt ? {alt:node.attrs.alt.slice(0,500)} : {})});return;}
      if (node.type === 'text') text += node.text;
      if (['inlineMath','blockMath'].includes(node.type)) text += ' $' + node.attrs.latex + '$ ';
      if (node.type === 'hardBreak') text += '\n';
      for (const child of node.content || []) walk(child);
      if (['paragraph','heading','blockquote','listItem','tableRow','codeBlock','blockMath'].includes(node.type)) text += '\n';
      if (['tableCell','tableHeader'].includes(node.type)) text += '\t';
    }
    walk(doc);flush();return output;
  }
  function target(data, targetId) {
    if (targetId === undefined && data.parts.length === 1) return data.parts[0];
    const part = data.parts.find(part => part.id === targetId);
    if (!part) throw new Error('请选择有效的小问');
    return part;
  }
  function referenceBlocks(part) {
    const reference = blocks(part.answerAndExplanation);
    return reference.length ? reference : [{type:'text',text:'未提供参考答案或评分细则。请依据题干明确要求评价作答；不要虚构参考答案或官方评分标准。题干信息不足以可靠评分时，请在反馈中明确说明，供人工复核。'}];
  }
  function aiInput(data, answer, targetId) {
    const part = target(data, targetId);
    if (!areaNonempty(answer.responses[part.id])) throw new Error('该小问尚未作答');
    return {protocolVersion:1,question:[...blocks(data.material),...blocks(part.stem)],answer:answerBlocks(answer.responses[part.id]),referenceAnswer:referenceBlocks(part),maxScore:part.maxScore,scoreStep:0.5};
  }
  QF.defineType({
    validateQuestion(data) {
      return object(data) && data.formatVersion === 1 && (data.material === null || document(data.material)) && Array.isArray(data.parts) && data.parts.length > 0 && data.parts.length <= 16 && data.parts.every(part => object(part) && (part.answerHeight === undefined || Number.isInteger(part.answerHeight) && part.answerHeight >= 120 && part.answerHeight <= 3200) && /^[A-Za-z0-9_-]{1,64}$/.test(part.id) && document(part.stem) && nonempty(part.stem) && document(part.answerAndExplanation) && Number.isFinite(part.maxScore) && part.maxScore > 0 && part.maxScore <= 100000 && Number.isInteger(part.maxScore * 2)) && new Set(data.parts.map(part => part.id)).size === data.parts.length && total(data) <= 100000 && data.parts.reduce((sum,part) => sum + height(part),0) <= 12000 && data.parts.reduce((sum,part) => sum + 760 * height(part),0) <= 8000000;
    },
    validateAnswer: answerValid,
    project(data, state) {return {formatVersion:1,material:data.material,parts:data.parts.map(part => ({id:part.id,stem:part.stem,maxScore:part.maxScore,answerHeight:height(part),...(state?.submitted ? {answerAndExplanation:part.answerAndExplanation} : {})}))};},
    grade(data, answer) {
      if (!answerValid(answer, data)) throw new Error('作答格式无效');
      // Whole-question answers stay pending until an explicit human/AI review.
      // Keep the legacy multi-part review aggregate unchanged.
      if (data.parts.length === 1 && areaNonempty(answer.responses[data.parts[0].id])) {
        return {gradingStatus:'pending',score:null,maxScore:total(data),correct:null,feedback:null,parts:{[data.parts[0].id]:{score:null,feedback:null,answered:true}}};
      }
      return result(data, Object.fromEntries(data.parts.map(part => [part.id,{score:0,feedback:null,answered:areaNonempty(answer.responses[part.id])}])));
    },
    review(data, answer, review, context) {
      if (!object(review) || Object.keys(review).some(key => !['score','feedback','targetId'].includes(key)) || !Number.isFinite(review.score) || review.feedback != null && (typeof review.feedback !== 'string' || review.feedback.length > 40000)) throw new Error('评分数据无效');
      const part = target(data, review.targetId);
      if (!areaNonempty(answer.responses[part.id])) throw new Error('该小问尚未作答');
      const parts = Object.fromEntries(data.parts.map(item => [item.id,{score:context?.state?.result?.parts?.[item.id]?.score || 0,feedback:context?.state?.result?.parts?.[item.id]?.feedback || null,answered:areaNonempty(answer.responses[item.id])}]));
      const score = Math.round(Math.max(0,Math.min(part.maxScore,review.score)) * 2) / 2;
      parts[part.id] = {score,feedback:review.feedback === undefined ? parts[part.id].feedback : review.feedback,answered:true};
      return result(data, parts);
    },
    getScore(data, state) {
      if (!state?.submitted && state?.status !== 'submitted') return {score:0,maxScore:total(data)};
      if (state.result.gradingStatus === 'pending') return {score:null,maxScore:total(data),gradingStatus:'pending'};
      return {score:state.result.score,maxScore:total(data),gradingStatus:state.result.gradingStatus,outlineStates:data.parts.map(part => ({id:part.id,status:state.result.parts?.[part.id]?.answered === false ? 'unanswered' : state.result.parts?.[part.id]?.score === part.maxScore ? 'correct' : 'incorrect'}))};
    },
    getOutlineItems() {return [];},
    prepareAiGrading(data, answer, context = {}) {
      if (context.automatic) return {targets:data.parts.filter(part => areaNonempty(answer.responses[part.id])).map(part => ({targetId:part.id,input:aiInput(data, answer, part.id)}))};
      return aiInput(data, answer, context.targetId);
    }
  });
})();
