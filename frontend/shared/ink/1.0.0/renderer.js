// Shared by the viewport and frozen answer snapshots. Keep self-contained.
export function paintInkStroke(context,stroke){
  const points=stroke.points;if(!points.length)return;
  const width=point=>stroke.width*(.35+.65*point.pressure);
  context.strokeStyle=stroke.color;context.fillStyle=stroke.color;context.lineCap='round';context.lineJoin='round';
  const dot=point=>{context.beginPath();context.arc(point.x,point.y,width(point)/2,0,Math.PI*2);context.fill();};
  dot(points[0]);
  for(let index=1;index<points.length;index++){
    const previous=points[index-1],current=points[index];
    const from=index===1?previous:{x:(points[index-2].x+previous.x)/2,y:(points[index-2].y+previous.y)/2};
    const to=index===points.length-1?current:{x:(previous.x+current.x)/2,y:(previous.y+current.y)/2};
    context.lineWidth=(width(previous)+width(current))/2;context.beginPath();context.moveTo(from.x,from.y);context.quadraticCurveTo(previous.x,previous.y,to.x,to.y);context.stroke();
  }
  if(points.length>1)dot(points.at(-1));
}
