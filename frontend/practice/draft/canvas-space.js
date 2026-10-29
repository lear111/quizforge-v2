// DOM rectangles use client coordinates; canvas drawing uses its local CSS
// dimensions before backing-store DPR. Use the same conversion for input,
// iframe hit regions and answer crops, including scaled browser layouts.
export function canvasSpace(canvas,{width,height}={}){
  const bounds=canvas.getBoundingClientRect();
  const localWidth=width||canvas.clientWidth||bounds.width||1,localHeight=height||canvas.clientHeight||bounds.height||1;
  const scaleX=localWidth/(bounds.width||localWidth),scaleY=localHeight/(bounds.height||localHeight);
  return {
    point:({x,y})=>({x:(x-bounds.left)*scaleX,y:(y-bounds.top)*scaleY}),
    rect:({left,top,width,height})=>({x:(left-bounds.left)*scaleX,y:(top-bounds.top)*scaleY,width:width*scaleX,height:height*scaleY}),
  };
}
