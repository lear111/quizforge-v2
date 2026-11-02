// 草稿只更新状态与版本；题目安全投影、白板和能力沿用已加载的读题响应。
export function mergeDraftResponse(previous,saved){
  if(!previous)return null;
  return {...previous,state:saved.state,stamp:saved.stamp,revision:saved.revision};
}
