// Pure, self-contained model, shared by the host rasterizer and the frozen page.
import {paintInkStroke} from '../../ink/1.0.0/renderer.js';
export function answerAreaModel(paintStroke=paintInkStroke) {
  const limits=Object.freeze({areas:16,width:1600,height:3200,totalHeight:12000,pixels:4000000,strokes:1000,points:20000,bytes:2000000,text:20000});
  const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
  const finite=(value,min,max)=>typeof value==='number'&&Number.isFinite(value)&&value>=min&&value<=max;
  const identifier=value=>typeof value==='string'&&/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(value);
  const color=value=>typeof value==='string'&&/^#[0-9a-f]{6}$/i.test(value);
  const invalid=()=>{throw new TypeError('手写答题区数据无效或超出限制，原答案仍保留');};
  function normalize(value,{snapshot=true}={}) {
    if(!object(value)||value.type!=='qf-answer-area'||value.formatVersion!==1||!identifier(value.id)||
      !Number.isInteger(value.width)||!Number.isInteger(value.height)||!finite(value.width,160,limits.width)||!finite(value.height,120,limits.height)||value.width*value.height>limits.pixels||
      value.targetId!==undefined&&!identifier(value.targetId)||!Array.isArray(value.strokes)||value.strokes.length>limits.strokes||
      value.text!==undefined&&(typeof value.text!=='string'||value.text.length>limits.text)||
      value.inkSource!==undefined&&value.inkSource!=='draft'||
      value.anchor!==undefined&&(!object(value.anchor)||Object.keys(value.anchor).some(key=>!['x','y','width','height'].includes(key))||!finite(value.anchor.x,-1000000,1000000)||!finite(value.anchor.y,-1000000,1000000)||!finite(value.anchor.width,1,10000)||!finite(value.anchor.height,1,20000))||
      Object.keys(value).some(key=>!['type','formatVersion','id','targetId','width','height','strokes','snapshot','text','inkSource','anchor'].includes(key)))invalid();
    let count=0;const seen=new Set();
    const strokes=value.strokes.map(stroke=>{
      if(!object(stroke)||!identifier(stroke.id)||seen.has(stroke.id)||!color(stroke.color)||!finite(stroke.width,value.inkSource==='draft'?.05:.5,value.inkSource==='draft'?64:16)||!Array.isArray(stroke.points)||!stroke.points.length||stroke.points.length>10000||
        Object.keys(stroke).some(key=>!['id','color','width','points'].includes(key))||(count+=stroke.points.length)>limits.points)invalid();
      seen.add(stroke.id);
      return {id:stroke.id,color:stroke.color.toLowerCase(),width:stroke.width,points:stroke.points.map(point=>{
        if(!object(point)||Object.keys(point).some(key=>!['x','y','pressure'].includes(key))||!finite(point.x,value.inkSource==='draft'?-2000000:0,value.inkSource==='draft'?2000000:value.width)||!finite(point.y,value.inkSource==='draft'?-2000000:0,value.inkSource==='draft'?2000000:value.height)||!finite(point.pressure,0,1))invalid();
        return {x:point.x,y:point.y,pressure:point.pressure};
      })};
    });
    const result={type:'qf-answer-area',formatVersion:1,id:value.id,...(value.targetId===undefined?{}:{targetId:value.targetId}),width:value.width,height:value.height,strokes,...(value.text===undefined?{}:{text:value.text}),...(value.inkSource===undefined?{}:{inkSource:value.inkSource})};
    if(value.anchor!==undefined)result.anchor={...value.anchor};
    if(value.snapshot!==undefined){
      const image=value.snapshot;
      if(!object(image)||Object.keys(image).some(key=>!['type','attrs'].includes(key))||image.type!=='image'||!object(image.attrs)||Object.keys(image.attrs).some(key=>key!=='assetId')||!/^[a-f0-9]{64}$/.test(image.attrs.assetId))invalid();
      if(snapshot)result.snapshot={type:'image',attrs:{assetId:image.attrs.assetId}};
    }
    if(JSON.stringify(result).length>limits.bytes)invalid();
    return result;
  }
  function create({id,targetId,width=760,height=480}) {return normalize({type:'qf-answer-area',formatVersion:1,id,...(targetId===undefined?{}:{targetId}),width,height,strokes:[]});}
  const textStyle=Object.freeze({font:'18px Arial, "Microsoft YaHei", "Noto Sans CJK SC", sans-serif',size:18,lineHeight:29,padding:16,color:'#263447'});
  function textLayout(context,document){
    context.font=textStyle.font;
    context.fontKerning='none';
    const lines=[''],available=document.width-textStyle.padding*2;let used=0;
    for(const character of (document.text||'').replace(/\r\n?/g,'\n').replace(/\t/g,'    ')){
      if(character==='\n'){lines.push('');used=0;continue;}
      const measured=context.measureText?.(character)?.width;
      const width=Number.isFinite(measured)?measured:character.codePointAt(0)>=0x2e80?18:10;
      if(used+width>available&&lines.at(-1)){lines.push('');used=0;}
      lines[lines.length-1]+=character;used+=width;
    }
    return {lines,height:textStyle.padding*2+lines.length*textStyle.lineHeight};
  }
  function paint(context,document,{background=true,text=true}={}) {
    context.clearRect(0,0,document.width,document.height);
    if(background){context.fillStyle='#ffffff';context.fillRect(0,0,document.width,document.height);}
    if(text&&document.text){
      const {lines}=textLayout(context,document);context.fillStyle=textStyle.color;context.textBaseline='top';
      for(let index=0;index<lines.length;index++)context.fillText?.(lines[index],textStyle.padding,textStyle.padding+index*textStyle.lineHeight);
    }
    context.lineCap='round';context.lineJoin='round';
    for(const stroke of document.strokes){
      if(document.inkSource==='draft'){paintStroke(context,stroke);continue;}
      context.fillStyle=stroke.color;context.strokeStyle=stroke.color;
      for(let i=0;i<stroke.points.length;i++){
        const point=stroke.points[i],radius=stroke.width*(.35+.65*point.pressure)/2;
        context.beginPath();context.arc(point.x,point.y,radius,0,Math.PI*2);context.fill();
        if(i){const previous=stroke.points[i-1];context.lineWidth=stroke.width*(.35+.65*(point.pressure+previous.pressure)/2);context.beginPath();context.moveTo(previous.x,previous.y);context.lineTo(point.x,point.y);context.stroke();}
      }
    }
  }
  return Object.freeze({limits,normalize,create,paint,identifier,textStyle,textLayout});
}
