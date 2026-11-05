import http from 'node:http';

// 只监听 app 的回环地址；验收不需要真实 Key，也不访问任何模型服务商。
http.createServer(async (request,response)=>{
  if(request.url==='/health'){response.end('ok');return;}
  if(request.method!=='POST'||request.url!=='/v1/chat/completions') {response.writeHead(404).end();return;}
  let size=0;
  for await(const chunk of request){size+=chunk.length;if(size>20*1024*1024){response.writeHead(413).end();return;}}
  response.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({
    id:'local-ai-grading',object:'chat.completion',created:1,model:'qwen-fake-grading',
    choices:[{index:0,message:{role:'assistant',content:JSON.stringify({score:2,feedback:'本机假模型：作答抓住要点，建议补充说明。'})},finish_reason:'stop'}],
    usage:{prompt_tokens:1000,completion_tokens:500,total_tokens:1500}
  }));
}).listen(9105,'127.0.0.1');
