/* Synthetic review fixtures only. No API client, credentials or persistence. */
window.PROTOTYPE = Object.freeze({
  groups: [
    {id:'workspace', number:'01', title:'练习空间', note:'统一导航、题库列表、大纲与 AI 的位置', kind:'practice', scenes:['作答中','已评分','整轮完成']},
    {id:'detail', number:'02', title:'题库详情与按本加入', note:'选择一本或多本，保留上一公开版本', kind:'hub', scenes:['新版本审核中','当前公开版本','已经加入']},
    {id:'library', number:'03', title:'题库列表状态', note:'沿用左侧列表，展示作者已移除与独立导入', kind:'practice', scenes:['全部状态','空列表']},
    {id:'overview', number:'04', title:'题库概览与更新', note:'自动更新后的题目状态与保留历史', kind:'practice', scenes:['练习中','作者已移除','已自动更新']},
    {id:'answer', number:'05', title:'作答区状态', note:'题目已更新、409 冲突与 503 暂不可用', kind:'practice', scenes:['本题已更新','保存冲突','暂不可用','保存恢复']},
    {id:'summary', number:'06', title:'本轮得分卡', note:'本轮结果、冻结历史与待评分状态', kind:'practice', scenes:['本轮进行中','待评分','本轮已完成']},
    {id:'tutor', number:'07', title:'AI 辅导', note:'答题中、已评分、重做、切题与异常', kind:'practice', scenes:['答题中','已评分','重做后','请求超时','额度不足','服务不可用']},
    {id:'publishing', number:'08', title:'上传与我的分享', note:'新版本上传、双版本状态与删除确认', kind:'hub', scenes:['我的分享','上传新版本','新版本审核中','上传冲突']}
  ],
  user: {name:'林同学', initial:'林', email:'lin@example.test', balance:'12.48'},
  pack: {title:'2027 考研数学 · 高数专项', author:'知行题库', category:'考研数学', version:'1.2', revision:12, nextVersion:'1.3', nextRevision:13, date:'2026-10-26', downloads:'268', comments:'12', filename:'高数专项.qbank', size:'24.6 MB', summary:'从函数与极限到积分应用，按章节练习，保留每一次思考。', description:'本包包含三本独立题库。你可以只加入正在学习的章节，也可以一次加入多本。每本题库都有独立的练习进度。', changeNote:'修正第 3、8、12 题的题干与解析，新增积分应用练习。'},
  banks: [
    {id:'limits', key:'question-banks/高数专项/函数与极限/', title:'函数与极限', questions:24, version:'1.2'},
    {id:'derivatives', key:'question-banks/高数专项/导数与微分/', title:'导数与微分', questions:32, version:'1.2'},
    {id:'integrals', key:'question-banks/高数专项/积分及应用/', title:'积分及应用', questions:28, version:'1.2'}
  ],
  collections: [
    {title:'函数与极限 · 我的复习', group:'考研数学', subtitle:'已提交 5 / 24 题', update:'pending', version:'1.2', next:'1.3'},
    {title:'导数与微分', group:'考研数学', subtitle:'已提交 0 / 32 题', update:'current', version:'1.2'},
    {title:'2025 英语阅读精选', group:'语言学习', subtitle:'已提交 8 / 20 题', update:'removed', version:'1.1'},
    {title:'课堂练习 · 第三周', group:'我的导入', subtitle:'独立导入 · 不自动更新', update:'independent', version:'1.0'}
  ],
  question: {number:3, total:24, title:'连续性与参数取值', type:'简答题', points:10, prompt:'已知函数 f(x) 在 x = 0 处连续。请写出求参数 a 的思路，并说明使用了什么条件。', math:'f(x) = { (eˣ − 1) / x，x ≠ 0；a，x = 0 }', draft:'我先计算 x 趋于 0 时的极限，再与函数在 0 处的值比较。', score:8, retries:2},
  outline: ['已评分','已评分','作答中','未作答','已评分','未作答','未作答','本题已更新','未作答','已评分','未作答','本题已更新','未作答','未作答','已评分','未作答','未作答','未作答','未作答','未作答','未作答','未作答','未作答','未作答'],
  summary: {score:38, max:50, submitted:5, unanswered:19, pending:1, date:'2026-10-27 14:20', elapsed:'32 分钟'},
  history: [{date:'2026-10-27 14:20', status:'因题库更新而中断', score:'38 / 50', version:'1.2'}, {date:'2026-10-25 20:16', status:'已完成', score:'42 / 50', version:'1.1'}]
});
