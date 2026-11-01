import {emptyDraft} from './whiteboard-model.js';

export function layerDocument(board,layer='draft') {
  const active=board?.[layer]||emptyDraft(),other=board?.[layer==='draft'?'notes':'draft']||emptyDraft();
  return structuredClone({...active,...(!active.layout&&other.layout?{layout:other.layout}:{}),strokes:[...other.strokes,...active.strokes]});
}
export function protectedInk(board,layer='draft') {
  const other=board?.[layer==='draft'?'notes':'draft']?.strokes||[];
  return layer==='notes'?[...other,...(board?.notes?.strokes||[])]:other;
}
export function layerPayload(board,layer,draft) {
  const excluded=new Set((board?.[layer==='draft'?'notes':'draft']?.strokes||[]).map(stroke=>stroke.id));
  return {layer,draft:{...draft,strokes:draft.strokes.filter(stroke=>!excluded.has(stroke.id))}};
}
export function mergeWhiteboard(cached,response) {
  // No answer, revision or stamp from an ink-only response can acknowledge an answer write.
  return {...cached,draft:response.draft,whiteboard:response.whiteboard};
}
