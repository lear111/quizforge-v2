/* Clickable static scenes. Buttons swap fixtures, not real application state. */
(() => {
  'use strict';
  const D=window.PROTOTYPE, body=document.body, app=document.getElementById('prototype');
  const group=D.groups.find(item=>item.id===body.dataset.group), size=body.dataset.size;
  const esc=value=>String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const url=(id,scene)=>{const g=D.groups.find(item=>item.id===id);return `${g.number}-${g.id}-${size}.html${scene?'?scene='+encodeURIComponent(scene):''}`;};
  const button=(label,action,style='')=>`<button type="button" class="button ${style}" data-ui="${action}">${label}</button>`;
  const pill=(label,color='')=>`<span class="pill ${color}">${label}</span>`;
  const callout=(title,text,style='',actions='')=>`<section class="callout ${style}" role="status"><div><strong>${title}</strong>${text?`<p>${text}</p>`:''}</div>${actions?`<div class="button-row">${actions}</div>`:''}</section>`;
  let scene=new URLSearchParams(location.search).get('scene');if(!group.scenes.includes(scene))scene=group.scenes[0];
  let tutorHidden=false,altQuestion=false,toastTimer;
  let panelTab=['workspace','tutor'].includes(group.id)?'ai':'outline',panelOpen=size==='desktop'||['workspace','tutor'].includes(group.id);
  let panelWidth=280,panelHeight=null,panelDrag=null;
  const navigation=()=>group.kind==='practice'?`<nav class="v2-practice-nav" aria-label="统一站点导航"><a href="${url('detail')}">题库广场</a><a href="${url('workspace')}" aria-current="page">练习空间</a><span class="spacer"></span><a href="#account" data-ui="account">${D.user.name} · 个人中心</a><button type="button" data-ui="logout">退出</button></nav>`:`<header class="site-header"><div class="container header-inner"><a class="brand" href="${url('detail')}"><img class="brand-mark" src="assets/upstream/quizforge-icon.svg" alt=""><div><div class="brand-name">QuizForge</div><div class="brand-caption">题库广场 · LEARN TOGETHER</div></div></a><nav class="main-nav" id="site-links"><a class="nav-link ${group.id==='detail'?'active':''}" href="${url('detail')}">发现题库</a><a class="nav-link ${group.id==='publishing'?'active':''}" href="${url('publishing')}">我的分享</a><a class="nav-link" href="#download" data-ui="download">下载软件</a></nav><div class="header-actions"><a class="practice-btn" href="${url('workspace')}">练习空间</a><a class="share-btn" href="${url('publishing','上传新版本')}">＋ <span class="share-text">分享题库</span></a><a class="user-chip" href="#account" data-ui="account"><span class="mini-avatar">${D.user.initial}</span>${D.user.name}</a><button type="button" class="mobile-menu-btn" data-ui="nav" aria-controls="site-links" aria-expanded="false" aria-label="打开站点导航">☰</button></div></div></header>`;
  const toolbar=()=>`<aside class="prototype-bar" aria-label="原型演示工具"><a href="index.html">← 原型目录</a><strong>${group.number} · ${group.title}</strong><small>假数据 · 不保存 · 不调用服务</small><label>状态<select id="scene" aria-label="选择演示状态">${group.scenes.map(name=>`<option${name===scene?' selected':''}>${name}</option>`).join('')}</select></label></aside>`;
  function sidebar(){
    return `<aside class="library" id="bank-sidebar" aria-label="我的题库列表"><div class="brand"><img class="brand-mark" src="assets/upstream/quizforge-icon.svg" width="36" height="36" alt=""><span>QuizForge<small>你的练习空间</small></span></div><div class="library-heading"><span>我的题库</span><button type="button" class="icon-button" data-ui="refresh-library" aria-label="刷新目录">↻</button></div><div class="library-list">${scene==='空列表'?'<p class="list-empty">还没有题库。去题库广场选择想练习的内容。</p>':['考研数学','语言学习','我的导入'].map(label=>`<section class="extension-group"><div class="library-group-header"><button class="extension-group-toggle" type="button" data-ui="folder" aria-expanded="true"><span class="extension-group-arrow">▾</span><span class="extension-group-label">${label}</span></button></div><div class="extension-group-body">${D.collections.filter(item=>item.group===label).map(item=>`<div class="library-row"><a class="library-item ${item.update==='pending'?'active':''}" href="${url('overview',item.update==='removed'?'作者已移除':undefined)}"><span class="item-icon">▤</span><span class="item-body"><span class="item-title"><span class="item-name">${esc(item.title)}</span></span><span class="item-meta">${item.update==='independent'?'独立导入':item.version+' · '+item.subtitle}</span>${item.update==='removed'?'<span class="item-meta">作者已移除 · 练习保留</span>':''}</span></a></div>`).join('')}</div></section>`).join('')}</div><div class="library-footer"><button type="button" class="icon-button" data-ui="appearance" title="外观设置" aria-label="外观设置">⚙</button><a class="library-download-link" href="${url('detail')}">获取题库 ↗</a></div></aside>`;
  }
  function outline(){
    return `<div class="outline-list"><section class="outline-group"><h2 class="outline-group-title">函数与极限</h2><nav class="outline-matrix" aria-label="题目导航">${D.outline.map((status,i)=>`<a class="outline-item ${status==='已评分'?'correct':i===2?'active draft':''} ${status==='本题已更新'?'updated':''}" href="${url('answer',status==='本题已更新'?'本题已更新':'保存恢复')}" aria-label="第${i+1}题，${status}" title="第${i+1}题 · ${status}"><span>${i+1}</span>${status==='本题已更新'?'<small class="outline-update-label">更新</small>':''}</a>`).join('')}</nav></section><a class="button small wide" href="${url('summary')}">本轮得分</a></div><div class="outline-legend"><span>已作答</span><span>答对</span><span>“更新”：作者修改了本题</span></div>`;
  }
  function workspaceHeader(title='函数与极限 · 我的复习',empty=false){
    const panelIcon='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></svg>';
    return `<section class="document-header"><button type="button" class="icon-button sidebar-toggle" data-ui="library-toggle" aria-label="显示或隐藏题库列表" aria-expanded="false" aria-controls="bank-sidebar">${panelIcon}</button><div class="document-identity">${empty?'':'<span class="document-group">考研数学</span>'}<h1>${empty?'选择一本题库':title}</h1></div><div class="document-actions">${empty?'':`<button type="button" class="header-action" data-ui="history" title="历史记录" aria-label="历史记录"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 11a9 9 0 1 1 3 7M3 4v7h7M12 7v5l3 2"/></svg></button><button type="button" class="draft-toggle" data-ui="draft"><span>✎</span> 草稿</button>`}</div><button type="button" class="icon-button sidebar-toggle" data-ui="outline-toggle" aria-label="大纲与AI辅导" title="大纲与AI辅导">${panelIcon}</button></section>`;
  }
  function questionCard({graded=false,updated=false,latest=false,alt=false}={}){
    const q=D.question, questionNumber=alt?4:q.number;
    return `<article class="question-card" aria-label="静态题卡示意"><div class="q-head">${pill(q.type)}<span data-field="question.maxScore">满分 ${q.points} 分</span></div><h2 data-field="question.title">${questionNumber}. ${alt?'导数的几何意义':q.title}</h2><p data-field="question.projectedData">${alt?'请说明函数在一点处的导数与切线斜率之间的关系。':q.prompt}</p>${alt?'':`<div class="math-line">${q.math}</div>`}<div class="response-area"><div class="response-label">下方区域的文字、手写和图片均作为答案进行评分。</div>${graded?`<div class="readonly-answer" data-field="question.answer">${q.draft}<br><br>所以 a = 1。</div>`:`<textarea aria-label="答题输入（原型不保存）">${updated?'':latest?'根据服务端最新记录：先求左右极限。':q.draft}</textarea>`}</div>${graded?`<div class="grade-box" data-field="question.result"><strong>本题 ${q.score} / ${q.points} 分</strong><br>思路正确，建议补充左右极限相等的论证。</div>`:''}<div class="button-row">${graded?button('重新作答','retry','small'):button('提交答案','submit','primary')}${graded?button('继续记草稿','draft','small'):''}</div><div class="save-label" data-field="question.saveStatus">${updated?'尚未作答':graded?'已提交 · 答案已锁定':scene==='保存冲突'?'尚未保存：存在其他页面的修改':scene==='暂不可用'?'输入保留在当前页面，尚未确认保存':'已保存（演示）'}</div><p class="stub-note">此题为静态示意，输入不会保存。</p></article>`;
  }
  function tutor(){

    const graded=scene==='已评分',redo=scene==='重做后',failed=['请求超时','额度不足','服务不可用'].includes(scene),number=altQuestion?4:3;
    const state=graded?'已评分':redo?'重做中':'答题中';
    const context=graded?'可以结合你的作答与评分讲解答案。':'只提供思路，不直接给答案。';
    const error=scene==='额度不足'?'可用额度不足，请兑换额度后继续。':scene==='请求超时'?'本次回答超时了，你的作答与保存不受影响。':'AI 服务暂时不可用，你仍然可以继续练习。';
    return `<section class="tutor-panel" id="tutor-panel" aria-label="AI 题目辅导"><span class="drawer-grip" aria-hidden="true"></span><header class="tutor-header"><div class="tutor-title"><h2>✧ AI 辅导</h2></div><div class="tutor-context" data-field="tutor.context"><div class="tutor-current"><strong>第 ${number} 题</strong><span>${state}${redo?' · 第 2 次重做':''}</span></div><p>${context}</p></div></header><div class="conversation" role="log" aria-label="本题对话" data-field="tutor.messages">${altQuestion?`<div class="chat"><div class="chat-label">新题目的对话</div><div class="bubble">已切换到第 4 题。可以从“导数的几何意义”开始提问；第 3 题的对话不会出现在这里。</div></div>`:`<div class="chat user"><div class="chat-label">你</div><div class="bubble">${graded?'我这一步为什么没有拿满分？':'我应该从哪个条件入手？'}</div></div>${failed?`<div class="tutor-error" role="status">${error}<div class="button-row" style="margin-top:10px">${scene==='额度不足'?button('兑换额度','redeem','small'):button('重试','ai-retry','small')}</div></div>`:`<div class="chat"><div class="chat-label">QuizForge AI</div><div class="bubble">${graded?'你用极限与函数值相等求出 a = 1，结论正确。':'先回到题目给出的“连续”这个条件。'}<div class="step">${graded?'这道题还需要说明左右极限都存在且相等。评分中的 2 分对应这一步。':'一个函数在某一点连续，需要极限值与什么相等？你可以先列出这个关系。'}</div>${redo?'这次可以先用自己的话解释连续的定义，再写算式。':graded?'可以把极限计算与连续性条件分成两步写，论证会更完整。':'先写出你认为成立的等式，我们再一起检查。'}</div></div>`}`}</div><div class="tutor-foot"><div class="tutor-composer"><label class="sr-only" for="ai-message">向当前题目的 AI 辅导提问</label><textarea id="ai-message" placeholder="围绕本题提问…" ${scene==='额度不足'?'disabled':''}></textarea><div class="composer-actions"><span class="composer-hint">仅针对当前题目</span><button class="button primary small" type="button" data-ui="ai-send" ${scene==='额度不足'?'disabled':''}>发送 ↑</button></div></div><div class="tutor-bottomline"><span class="quota" data-field="ai.balance">额度 ¥${scene==='额度不足'?'0.00':D.user.balance} · <a class="text-link" href="#redeem" data-ui="redeem">兑换</a></span><span class="ai-disclaimer">AI 可能出错，请核实</span></div></div></section>`;
  }
  function frame(content,{ai=false,libraryMode=false,title}={}){
    const panel=`<aside class="outline shared-panel ${panelOpen?'open':''}" id="shared-panel" aria-label="大纲与 AI 共用区域"><div class="panel-resize" role="separator" tabindex="0" aria-label="${size==='mobile'?'拖动调整侧栏高度':'拖动调整侧栏宽度'}" aria-orientation="${size==='mobile'?'horizontal':'vertical'}" aria-controls="shared-panel" aria-valuemin="240" aria-valuemax="560" aria-valuenow="${size==='mobile'?panelHeight||360:panelWidth}" title="${size==='mobile'?'上下拖动调整高度，也可用上下方向键':'左右拖动调整宽度，也可用左右方向键'}"></div><div class="outline-heading panel-tabs" role="tablist" aria-label="侧栏内容"><button type="button" role="tab" data-ui="panel-outline" aria-selected="${panelTab==='outline'}">题目大纲</button><button type="button" role="tab" data-ui="panel-ai" aria-selected="${panelTab==='ai'}">AI 辅导</button><button class="close-button panel-close" type="button" data-ui="ai-close" aria-label="关闭侧栏">×</button></div><div class="shared-panel-body" data-panel="outline" role="tabpanel" aria-label="题目大纲" ${panelTab==='outline'?'':'hidden'}>${outline()}</div><div class="shared-panel-body" data-panel="ai" role="tabpanel" aria-label="AI 辅导" ${panelTab==='ai'?'':'hidden'}>${tutor()}</div></aside>`;
    return `<div class="app-shell prototype-app ${libraryMode?'library-demo':''}" style="--outline:${panelWidth}px;--panel-height:${panelHeight===null?'65%':panelHeight+'px'}">${sidebar()}<main class="main workspace-main">${workspaceHeader(title,libraryMode)}<div class="panes"><div class="question-pane"><div class="question-viewport"><div class="question-frame-wrapper">${content}</div></div></div>${libraryMode?'':`<nav class="question-navigation" aria-label="切换题目"><button type="button" class="question-arrow previous" data-ui="previous" aria-label="上一题"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7"/></svg></button><button type="button" class="question-arrow next" data-ui="next" aria-label="下一题"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m10 5 7 7-7 7"/></svg></button></nav>`}</div></main>${panel}</div>`;
  }
  function workspace(){
    return frame(scene==='整轮完成'?summaryCard(true):questionCard({graded:scene==='已评分',alt:altQuestion}),{ai:true});
  }
  function library(){
    return frame('<div class="welcome"><span class="welcome-icon">▤</span><h2>从一道题开始</h2><p>选择左侧题库，开始练习。</p></div>',{libraryMode:true});
  }
  function overview(){
    const pending=scene==='练习中',removed=scene==='作者已移除';
    const notice=pending?'':removed?callout('作者已移除该题库，不再更新','你的练习、已保存作答和历史仍然保留。','neutral'):'';
    return frame(`<section class="overview-panel">${notice}<h2 data-field="collection.title">${removed?'2025 英语阅读精选':'函数与极限 · 我的复习'}</h2><div class="overview-meta" data-field="collection.metadata"><span>分组：${removed?'语言学习':'考研数学'}</span><span>当前版本 ${scene==='已自动更新'?'1.3':removed?'1.1':'1.2'}</span><span>24 张题卡</span><span>5 题已提交</span></div><div class="button-row"><a class="button primary" href="${url('answer',scene==='已自动更新'?'本题已更新':'保存恢复')}">继续练习</a><a class="button" href="${url('summary')}">本轮得分</a>${button('查看历史','history')}</div><div class="question-list">${['极限存在的条件','左右极限的比较','连续性与参数取值','无穷小量的比较','间断点的分类','分段函数的连续性'].map((title,i)=>`<a class="question-row" href="${url('answer',i===2&&scene==='已自动更新'?'本题已更新':'保存恢复')}"><span class="q-number">${i+1}</span><span class="q-description"><strong>${title}</strong><small>简答题 · 满分 10 分</small></span>${i===2&&scene==='已自动更新'?pill('本题已更新'):i<2?pill('已评分','green'):pill(i===2?'作答中':'尚未作答','gray')}</a>`).join('')}</div></section>`,{title:removed?'2025 英语阅读精选':'函数与极限 · 我的复习'});
  }
  function answer(){
    const notice=scene==='本题已更新'?callout('本题已更新','作者修改了本题，原作答和笔迹已清空。未变化的题目不受影响。'):scene==='保存冲突'?callout('该题已在其他页面更新','当前输入尚未保存，请先保留你的输入，再刷新本题。','error',button('刷新本题','refresh-question','small')):scene==='暂不可用'?callout('暂时无法保存，已保留你的输入，正在重试…','恢复前请不要关闭或刷新页面。','warning'):callout('已读取当前记录','下方显示保存后的作答状态。','success');
    return frame(notice+questionCard({updated:scene==='本题已更新',latest:scene==='保存恢复'}));
  }
  function summaryCard(finished=false){
    const pending=scene==='待评分',s=D.summary;
    return `<section class="practice-summary-card"><header class="practice-summary-header"><span class="practice-summary-badge">分值汇总</span><h2 class="practice-summary-title">${finished?'本轮已完成':'本轮练习'}</h2><p class="practice-summary-intro">${finished?'历史已保留，可以继续下一轮练习。':'本轮结果来自当前练习记录。'}</p></header><div class="practice-summary-body"><p class="practice-summary-score-label">已提交题目得分 / 已提交题目满分</p><div class="practice-summary-score" data-field="summary.score"><span class="practice-summary-earned">${s.score}</span><span class="practice-summary-separator">/</span><span class="practice-summary-maximum">${s.max}</span></div><div class="practice-summary-counts" data-field="summary.counts"><span class="practice-summary-count">已提交 ${s.submitted} 题</span><span class="practice-summary-count">未提交 ${s.unanswered} 题</span>${pending?'<span class="practice-summary-count">待评分 1 题</span>':''}</div><p class="small-copy muted" data-field="summary.elapsed">本轮已练习 ${s.elapsed}</p></div><footer class="practice-summary-footer"><p class="practice-summary-note">${finished?'已完成轮次使用 1.2 的题目、作答与评分快照，不受之后更新影响。':pending?'仍有 1 题待评分，评分结束后才能完成本轮。':'完成练习后保留本轮结果。'}</p><div class="button-row" style="justify-content:center">${finished?`${button('查看本轮历史','completed-history')}<a class="button primary" href="${url('workspace')}">返回练习空间</a>`:`<button type="button" class="practice-summary-finish" data-ui="finish" ${pending?'disabled':''}>完成本轮</button><a class="button" href="${url('answer','保存恢复')}">继续练习</a>`}</div></footer></section>`;
  }
  function summary(){return frame(summaryCard(scene==='本轮已完成'));}
  function tutorScreen(){return frame(questionCard({graded:scene==='已评分',alt:altQuestion}),{ai:true});}
  function detail(){
    const p=D.pack,pending=scene==='新版本审核中';
    return `<main class="page-container"><nav class="crumbs" aria-label="面包屑"><a href="index.html">← 发现题库</a><span>/</span><span>${p.title}</span></nav><div class="detail-layout"><header class="d-head">${pending?`<div class="inline-notice">${callout('新版本审核中，当前显示上一版本','新版本通过审核后，已加入的题库将按练习状态更新。','warning')}</div>`:''}<div class="badges">${pill(p.category)}</div><h1 data-field="pack.title">${p.title}</h1><div class="metadata-line" data-field="pack.metadata"><span>${p.author}</span><span>${p.date}</span><span>版本 ${p.version}</span><span>${p.downloads} 次下载</span></div><p class="d-summary" data-field="pack.summary">${p.summary}</p></header><aside class="side" aria-label="获取题库"><div class="panel"><div class="file-chip"><span class="file-icon">▤</span><div><div class="file-name" data-field="pack.archive.name">${p.filename}</div><div class="file-sub" data-field="pack.archive.size">${p.size} · 3 本题库</div></div></div><div class="button-row"><button type="button" class="button" data-ui="download">下载</button><button type="button" class="button primary" data-ui="join">${scene==='已经加入'?'打开我的题库':'加入我的题库'}</button></div><p class="small-copy muted">加入前可在目录选择一本或多本。再次加入同一本会打开已有题库。</p><div class="review-mini">当前可用版本：1.2${pending?'<br>作者上传的新版本正在审核。':''}</div><a class="prototype-detail-action" href="${url('workspace')}">前往练习空间 →</a></div></aside><div class="d-body"><section class="d-section"><h2>题库介绍</h2><p data-field="pack.description">${p.description}</p></section><section class="d-section"><h2>包含题库 <small>3 本</small></h2><div class="bank-list" data-field="pack.catalog"><div class="prototype-bank-row"><input id="select-all" type="checkbox" data-ui="select-all"><label for="select-all">全选</label></div>${D.banks.map((bank,i)=>`<div class="prototype-bank-row"><input type="checkbox" id="bank-${bank.id}" value="${bank.key}" data-bank-select ${i===0?'checked':''}><label for="bank-${bank.id}"><strong>${bank.title}</strong><small>${bank.questions} 张题卡 · 版本 ${bank.version}</small></label></div>`).join('')}<div class="selected-note"><span id="selection-label">已选 1 / 3 本</span><button type="button" class="button primary small" data-ui="join-selected">加入所选题库</button></div></div></section><section class="d-section"><h2>题库截图 <small>示意</small></h2><div class="pack-shots"><div class="mock-shot" aria-label="静态练习页缩略示意">函数与极限<br>练习空间预览</div><div class="mock-shot" aria-label="静态评分页缩略示意">作答与评分<br>本轮得分预览</div></div></section><section class="d-section"><h2>交流与反馈 <small data-field="pack.commentCount">${p.comments} 条</small></h2><p class="muted">本次原型不展开原样复用的评论功能。</p></section></div></div></main>`;
  }
  function shareCards(){
    return `<div class="prototype-share-grid"><article class="quiz-card"><div class="card-art"><div class="cover-fallback" aria-label="高数封面占位">∫</div></div><div class="card-main"><h2 class="card-title">${D.pack.title}</h2><p class="card-desc">函数、导数与积分的专项练习。</p><div class="author-row">知行题库 · 2026-10-26</div><div class="share-version-state"><div data-field="share.approved"><span>公开版本 <strong>1.2</strong></span>${pill('已公开','green')}</div><div data-field="share.pending"><span>新版本 <strong>1.3</strong></span>${pill('审核中','amber')}</div></div></div><div class="card-bottom"><div class="card-buttons"><a class="button small" href="${url('detail')}">详情</a><button class="button small" type="button" data-scene="上传新版本">上传新版本</button>${button('删除','delete-share','small danger')}</div></div></article><article class="quiz-card"><div class="card-art"><div class="cover-fallback" aria-label="线代封面占位">A</div></div><div class="card-main"><h2 class="card-title">线性代数 · 基础练习</h2><p class="card-desc">矩阵与线性方程组。</p><div class="author-row">知行题库 · 2026-10-20</div><div class="share-version-state"><div><span>公开版本 <strong>1.0</strong></span>${pill('已公开','green')}</div><div class="muted">暂无审核中版本</div></div></div><div class="card-bottom"><div class="card-buttons">${button('管理','manage-share','small')}${button('删除','delete-share','small danger')}</div></div></article></div>`;
  }
  function uploadForm(){
    const conflict=scene==='上传冲突';
    return `<div class="up-wrap"><header class="up-head"><h1>上传新版本</h1><p>${D.pack.title} · 当前公开版本 1.2 继续可用</p></header>${conflict?`<div class="inline-notice">${callout('上传信息已在其他页面更新','本页填写内容尚未提交，已保留。请核对最新版本后重新提交。','error',button('查看最新版本','latest-version','small'))}</div>`:''}<form class="up-card"><fieldset class="upload-fields"><button type="button" class="dropzone" data-ui="choose-file" aria-label="选择演示文件"><span aria-hidden="true">⇧</span><b id="mock-file-name">点击选择新的 .qbank 题库包</b><small id="mock-file-meta">演示附件，不会读取或上传文件</small></button><div class="field"><label class="f-label" for="pack-title">题库包名称</label><input class="input" id="pack-title" value="${D.pack.title}" aria-label="题库包名称"></div><div class="field"><label class="f-label" for="pack-intro">简介</label><textarea class="textarea" id="pack-intro" aria-label="题库简介">${D.pack.summary}</textarea></div><details class="more" open><summary>更多信息</summary><div class="more-grid"><div class="field"><label class="f-label" for="pack-version">版本号</label><input class="input" id="pack-version" value="1.3" aria-label="版本号"></div><div class="field"><label class="f-label">分类</label><span class="input">考研数学</span></div></div><div class="field"><label class="f-label" for="pack-change">更新说明（可选）</label><textarea class="textarea" id="pack-change" aria-label="更新说明">${D.pack.changeNote}</textarea></div></details><label class="upload-rights"><input type="checkbox" id="rights">我确认拥有分享题库及其材料的权利。</label><p class="prototype-upload-note">新版本审核通过后才公开；用户已完成的练习历史保持不变。</p></fieldset><div class="up-actions"><button type="button" class="button" data-scene="我的分享">取消</button><button type="button" class="button primary" data-ui="publish" id="publish-button" disabled>提交新版本</button></div></form></div>`;
  }
  function publishing(){
    return `<main class="page-container"><div class="page-heading"><div><h1>我的分享</h1><p>发布题库，并持续完善每一个版本。</p></div>${button('分享新题库','new-share','primary')}</div><nav class="section-tabs" aria-label="版本管理"><button class="button" type="button" data-scene="我的分享" aria-pressed="${scene==='我的分享'}">我的分享</button><button class="button" type="button" data-scene="上传新版本" aria-pressed="${['上传新版本','上传冲突'].includes(scene)}">上传新版本</button></nav>${scene==='我的分享'?shareCards():scene==='新版本审核中'?`<section class="surface success-panel"><div class="success-mark">✓</div><h2>新版本已提交，等待审核</h2><p class="small-copy muted">用户仍然可以查看、加入并练习公开版本 1.2。</p><table class="version-table"><thead><tr><th>版本</th><th>状态</th><th>用户看到的内容</th></tr></thead><tbody><tr><td>1.2</td><td>${pill('已公开','green')}</td><td>继续使用</td></tr><tr><td>1.3</td><td>${pill('审核中','amber')}</td><td>审核通过后更新</td></tr></tbody></table><div class="button-row" style="justify-content:center"><button type="button" class="button primary" data-scene="我的分享">返回我的分享</button><a class="button" href="${url('detail')}">查看公开页面</a></div></section>`:uploadForm()}</main>`;
  }
  function render(){
    const content={workspace,detail,library,overview,answer,summary,tutor:tutorScreen,publishing}[group.id]();
    app.innerHTML=toolbar()+navigation()+content+`<dialog class="prototype-dialog" id="modal" aria-labelledby="modal-title"><header class="modal-head"><h2 id="modal-title"></h2><button type="button" class="close-button" data-ui="modal-close" aria-label="关闭弹窗">×</button></header><div class="modal-body" id="modal-body"></div></dialog><div class="demo-signal" id="demo-signal" role="status" hidden></div>`;
    syncPanelSize();
  }
  function switchPanel(tab,open=true){
    panelTab=tab;panelOpen=open;
    const panel=document.getElementById('shared-panel');panel.classList.toggle('open',open);
    for(const content of panel.querySelectorAll('[data-panel]'))content.hidden=content.dataset.panel!==tab;
    panel.querySelector('[data-ui="panel-outline"]').setAttribute('aria-selected',String(tab==='outline'));
    panel.querySelector('[data-ui="panel-ai"]').setAttribute('aria-selected',String(tab==='ai'));
    syncPanelSize();
  }
  function panelLimits(){
    const shell=document.querySelector('.prototype-app');if(!shell)return null;
    const bounds=shell.getBoundingClientRect(),left=document.getElementById('bank-sidebar').getBoundingClientRect().width;
    // Leave room for the question, or for its header above the phone drawer.
    const max=size==='mobile'?Math.max(240,bounds.height-100):Math.max(240,Math.min(560,bounds.width-left-360));
    return {shell,min:240,max};
  }
  function syncPanelSize(value){
    const limits=panelLimits();if(!limits)return;
    const mobile=size==='mobile',panel=document.getElementById('shared-panel'),handle=panel.querySelector('.panel-resize');
    let current=value??(mobile?panelHeight:panelWidth);
    if(current!==null){current=Math.round(Math.max(limits.min,Math.min(limits.max,current)));if(mobile)panelHeight=current;else panelWidth=current;limits.shell.style.setProperty(mobile?'--panel-height':'--outline',current+'px');}
    handle.setAttribute('aria-valuemin',String(limits.min));handle.setAttribute('aria-valuemax',String(Math.floor(limits.max)));
    handle.setAttribute('aria-valuenow',String(current??Math.round(panel.getBoundingClientRect().height)));
  }
  app.addEventListener('pointerdown',event=>{
    const handle=event.target.closest('.panel-resize');if(!handle||event.button!==0)return;
    const panel=handle.parentElement,mobile=size==='mobile';
    panelDrag={id:event.pointerId,handle,start:mobile?event.clientY:event.clientX,extent:mobile?panel.getBoundingClientRect().height:panel.getBoundingClientRect().width};
    handle.setPointerCapture(event.pointerId);body.classList.add('resizing-panel');event.preventDefault();
  });
  app.addEventListener('pointermove',event=>{
    if(!panelDrag||event.pointerId!==panelDrag.id)return;
    syncPanelSize(panelDrag.extent+panelDrag.start-(size==='mobile'?event.clientY:event.clientX));
  });
  function endPanelDrag(event){if(panelDrag&&event.pointerId===panelDrag.id){panelDrag=null;body.classList.remove('resizing-panel');}}
  app.addEventListener('pointerup',endPanelDrag);app.addEventListener('pointercancel',endPanelDrag);app.addEventListener('lostpointercapture',endPanelDrag);
  app.addEventListener('keydown',event=>{
    if(!event.target.matches('.panel-resize'))return;
    const mobile=size==='mobile',grow=mobile?'ArrowUp':'ArrowLeft',shrink=mobile?'ArrowDown':'ArrowRight',limits=panelLimits();
    if(![grow,shrink,'Home','End'].includes(event.key)||!limits)return;
    const current=Number(event.target.getAttribute('aria-valuenow'));
    syncPanelSize(event.key==='Home'?limits.min:event.key==='End'?limits.max:current+(event.key===grow?20:-20));event.preventDefault();
  });
  window.addEventListener('resize',()=>syncPanelSize());
  function showDialog(title,content){document.getElementById('modal-title').textContent=title;document.getElementById('modal-body').innerHTML=content;document.getElementById('modal').showModal();}
  function closeDialog(){document.getElementById('modal').close();}
  function signal(text){const node=document.getElementById('demo-signal');clearTimeout(toastTimer);node.textContent='原型演示：'+text;node.hidden=false;toastTimer=setTimeout(()=>{node.hidden=true;},4500);}
  function setScene(value){if(!group.scenes.includes(value))return;scene=value;tutorHidden=false;altQuestion=false;render();}
  function selection(){const boxes=[...document.querySelectorAll('[data-bank-select]')],selected=boxes.filter(input=>input.checked);const label=document.getElementById('selection-label');if(label)label.textContent=`已选 ${selected.length} / ${boxes.length} 本`;const all=document.getElementById('select-all');if(all){all.checked=selected.length===boxes.length;all.indeterminate=selected.length>0&&selected.length<boxes.length;}return selected;}
  function selectedDialog(){
    const rows=selection();if(!rows.length){signal('请先选择至少一本题库。');return;}
    showDialog('确认加入我的题库',`<p>将加入以下题库。练习进度、作答与历史只属于你。</p><div>${rows.map(input=>{const bank=D.banks.find(item=>item.key===input.value);return `<div class="prototype-bank-row"><strong>${bank.title}</strong><span class="spacer"></span>${bank.questions} 张题卡</div>`;}).join('')}</div><p class="small-copy">已加入的题库会打开已有实例，不会再创建一份。</p><div class="button-row end">${button('取消','modal-close')}<a class="button primary" href="${url('workspace')}">确认加入（演示）</a></div>`);
  }
  app.addEventListener('submit',event=>event.preventDefault());
  app.addEventListener('change',event=>{
    if(event.target.id==='scene'){setScene(event.target.value);return;}
    if(event.target.matches('[data-bank-select]'))selection();
    if(event.target.id==='select-all'){for(const input of document.querySelectorAll('[data-bank-select]'))input.checked=event.target.checked;selection();}
    if(event.target.id==='rights')document.getElementById('publish-button').disabled=!event.target.checked;
  });
  app.addEventListener('click',event=>{
    const target=event.target.closest('[data-ui],[data-scene]');if(!target)return;
    if(target.dataset.scene){setScene(target.dataset.scene);return;}
    const action=target.dataset.ui;if(target.tagName==='A')event.preventDefault();
    switch(action){
      case 'nav': {const links=document.getElementById('site-links');links.classList.toggle('open');target.setAttribute('aria-expanded',String(links.classList.contains('open')));break;}
      case 'library-toggle': {const node=document.getElementById('bank-sidebar');node?.classList.toggle('open');if(size==='desktop')document.querySelector('.prototype-app').classList.toggle('library-hidden');document.querySelectorAll('[data-ui=library-toggle]').forEach(button=>button.setAttribute('aria-expanded',String(node?.classList.contains('open'))));break;}
      case 'outline-toggle':switchPanel(panelTab,!panelOpen);break;
      case 'ai-open':switchPanel('ai');break;
      case 'ai-close':switchPanel(panelTab,false);break;
      case 'panel-outline':switchPanel('outline');break;
      case 'panel-ai':switchPanel('ai');break;
      case 'folder': {const section=target.closest('.extension-group'),content=section.querySelector('.extension-group-body');content.hidden=!content.hidden;target.setAttribute('aria-expanded',String(!content.hidden));section.querySelector('.extension-group-arrow').textContent=content.hidden?'▸':'▾';break;}
      case 'appearance':signal('沿用现有外观设置，本原型不另做设置页。');break;
      case 'refresh-library':signal('静态列表，无真实刷新请求。');break;
      case 'next':case 'previous':altQuestion=!altQuestion;render();signal('已切换演示题目；AI 对话随题切换，没有发起模型请求。');break;
      case 'draft':signal('草稿白板沿用旧版；本次只演示宿主布局，不实现画笔或修改 Bridge。');break;
      case 'submit':if(group.id==='workspace'||group.id==='tutor')setScene('已评分');else signal('提交与评分仅为静态占位，不保存或计算分数。');break;
      case 'retry':if(group.id==='tutor')setScene('重做后');else signal('重新作答为占位入口；不会改动真实答案。');break;
      case 'refresh-question': {const input=document.querySelector('.response-area textarea');showDialog('刷新本题前，请保留输入',`<p>刷新会读取其他页面已经保存的记录。本页输入未确认保存，不会自动覆盖服务端内容。</p><label class="form-field">当前尚未保存的输入<textarea readonly aria-label="可复制保留的本页输入">${esc(input?.value||'')}</textarea></label><div class="button-row end">${button('返回保留输入','modal-close')}${button('读取最新记录','confirm-refresh','primary')}</div>`);break;}
      case 'confirm-refresh':setScene('保存恢复');break;
      case 'finish':showDialog('完成本轮练习？',`<p>本轮题目、作答与评分将保存为已完成历史。</p><div class="button-row end">${button('再检查一下','modal-close')}${button('完成本轮','confirm-finish','primary')}</div>`);break;
      case 'confirm-finish':setScene('本轮已完成');break;
      case 'history':showDialog('练习历史',`<p>历史内容与当时的题目、评分和版本一致，不会跟随作者更新。</p>${D.history.map(item=>`<div class="history-entry"><strong>${item.status}</strong>${item.date} · 版本 ${item.version}<br>已提交得分 ${item.score}</div>`).join('')}<div class="button-row end">${button('关闭','modal-close')}</div>`);break;
      case 'completed-history':showDialog('已完成历史 · 版本 1.2',`<p>2026-10-27 14:20 · 已完成 · 已提交得分 38 / 50</p><div class="surface small-copy">题目快照：连续性与参数取值<br>当时的作答：先比较极限与函数值。<br>当时评分：8 / 10 分<br><br>此处是只读快照示意，不随当前版本变化。</div><div class="button-row end">${button('关闭','modal-close')}</div>`);break;
      case 'join':if(scene==='已经加入'){location.href=url('workspace');}else selectedDialog();break;
      case 'join-selected':selectedDialog();break;
      case 'download':signal('下载入口保留；原型没有真实 .qbank 文件。');break;
      case 'choose-file':document.getElementById('mock-file-name').textContent='高数专项-1.3.qbank';document.getElementById('mock-file-meta').textContent='演示附件 · 24.8 MB · 3 本题库（不会读取本地文件）';break;
      case 'publish':setScene('新版本审核中');break;
      case 'delete-share':showDialog('删除这份分享？',`<p>分享将从题库广场移除，新用户不能再加入。</p>${callout('已加入的用户会继续保留该题库','他们的练习、作答与历史不受影响，但不再接收更新。','warning')}<div class="button-row end">${button('保留分享','modal-close')}${button('删除分享','confirm-delete','danger')}</div>`);break;
      case 'confirm-delete':closeDialog();signal('分享已移除状态示意；不会删除任何真实数据。');break;
      case 'manage-share':showDialog('管理分享',`<p>当前公开版本 1.0；没有审核中的新版本。</p><div class="button-row end">${button('关闭','modal-close')}</div>`);break;
      case 'new-share':showDialog('分享新题库',`<p>首次上传沿用旧 Hub 的题库发布表单。本组原型聚焦上传新版本。</p><div class="button-row end">${button('关闭','modal-close')}${button('查看新版本表单','show-upload','primary')}</div>`);break;
      case 'show-upload':setScene('上传新版本');break;
      case 'latest-version':showDialog('最新版本信息',`<p>另一页面已提交新版本 1.4，当前公开版本仍为 1.2。本页填写内容尚未保存。</p><p>本页输入还在。请先核对另一页面的版本，再决定是否重新上传。</p><div class="button-row end">${button('返回当前输入','modal-close')}</div>`);break;
      case 'ai-send': {const text=document.getElementById('ai-message');if(!text?.value.trim()){text?.focus();return;}const conversation=document.querySelector('.conversation'),user=document.createElement('div');user.className='chat user';const label=document.createElement('div');label.className='chat-label';label.textContent='你';const bubble=document.createElement('div');bubble.className='bubble';bubble.textContent=text.value;user.append(label,bubble);conversation.append(user);const reply=document.createElement('div');reply.className='bubble';reply.textContent='这是一条固定演示回复，不调用模型。我们可以先把已知条件分别列出来，再看它们之间的关系。';conversation.append(reply);text.value='';reply.scrollIntoView({block:'nearest'});break;}
      case 'ai-retry':setScene('答题中');signal('展示重试后的固定回复，没有真实调用。');break;
      case 'redeem':showDialog('兑换 AI 额度',`<p>当前可用额度 ¥${scene==='额度不足'?'0.00':D.user.balance}。此处复用账号额度入口。</p><label class="form-field">兑换码<input type="text" aria-label="演示兑换码" placeholder="原型不提交兑换码"></label><div class="button-row end">${button('取消','modal-close')}${button('兑换（演示）','redeem-demo','primary')}</div>`);break;
      case 'redeem-demo':closeDialog();signal('兑换流程占位，不验证代码、不变更余额。');break;
      case 'account':showDialog('个人中心',`<dl><dt>用户名</dt><dd>${D.user.name}</dd><dt>邮箱</dt><dd>${D.user.email}</dd><dt>AI 可用额度</dt><dd>¥${D.user.balance}</dd></dl><div class="button-row end"><a class="button primary" href="${url('workspace')}">练习空间</a>${button('关闭','modal-close')}</div>`);break;
      case 'logout':showDialog('退出登录',`<p>此入口复用账号退出流程。当前为静态原型，不操作真实会话。</p><div class="button-row end">${button('返回原型','modal-close')}</div>`);break;
      case 'modal-close':closeDialog();break;
      case 'select-all':break;
      default:signal('此入口仅展示交互位置。');
    }
  });
  render();
})();
