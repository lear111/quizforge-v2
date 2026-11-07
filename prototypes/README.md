# QuizForge V2 · 可点击界面原型 v1

**仅静态原型。** 不含接口调用、真实登录、上传、AI、持久化、规则计算或拓展 iframe / Bridge。所有姓名、版本、题目、分数、金额和对话均为合成数据。

打开 [index.html](index.html) 选择页面。可以直接双击 HTML；也可在仓库根目录运行：

```powershell
python -m http.server 8793 --bind 127.0.0.1 --directory prototypes
```

## 页面

| 界面组 | 桌面（1440 × 960） | 手机（390 × 844） | 演示状态 |
| --- | --- | --- | --- |
| 01 练习空间 | [桌面](01-workspace-desktop.html) | [手机](01-workspace-mobile.html) | 作答中、已评分、整轮完成 |
| 02 题库详情与按本加入 | [桌面](02-detail-desktop.html) | [手机](02-detail-mobile.html) | 新版本审核中、当前公开版本、已经加入 |
| 03 题库列表状态 | [桌面](03-library-desktop.html) | [手机](03-library-mobile.html) | 全部状态、空列表 |
| 04 题库概览与更新 | [桌面](04-overview-desktop.html) | [手机](04-overview-mobile.html) | 练习中、作者已移除、已自动更新 |
| 05 作答区状态 | [桌面](05-answer-desktop.html) | [手机](05-answer-mobile.html) | 本题已更新、保存冲突、暂不可用、保存恢复 |
| 06 本轮得分卡 | [桌面](06-summary-desktop.html) | [手机](06-summary-mobile.html) | 本轮进行中、待评分、本轮已完成 |
| 07 AI 辅导 | [桌面](07-tutor-desktop.html) | [手机](07-tutor-mobile.html) | 答题中、已评分、重做后、请求超时、额度不足、服务不可用 |
| 08 上传与我的分享 | [桌面](08-publishing-desktop.html) | [手机](08-publishing-mobile.html) | 我的分享、上传新版本、新版本审核中、上传冲突 |

顶部浅色状态工具条仅用于评审。切换演示状态、切题、刷新或离开页面会丢弃输入，不保存；这不代表正式产品的保存行为。

## 本次评审确认的调整

- 直接沿用现有 Web 的题库侧栏、顶部文档栏、浮动切题按钮和得分卡；Hub 沿用现有导航、题库卡片和单列表单，不另做整体外观设计。
- 统一入口叫“练习空间”。“我的题库”只是左侧列表标签。第 03 组用于展示该列表状态，中间保留原有欢迎区，不增加独立的产品页面。
- 普通用户不显示“有新版本”或“立即更新”。更新按服务端时机自动进行；仍展示“本题已更新”和“作者已移除”。不在前端实现更新策略。
- AI 辅导与题目大纲共用一个区域，通过两个按钮切换。桌面在左边界左右拖动，宽度 240～560px，并为题区保留空间；手机抽屉在顶部上下拖动，高度限制在当前练习区内。支持方向键和 Home / End。调整及切换标签不清空当前输入，尺寸仅保留在当前页面，刷新恢复默认。
- AI 使用白底对话、浅灰提问气泡与一体输入框；大纲以“更新”文字代替含义不清的角标小点。
- 上述调整来自用户在原型评审中的明确指示，与设计文档不同之处单列于 [GAPS.md](GAPS.md)。本任务不修改 docs。

## 可点击路径

1. 详情选一本或多本 → 确认加入 → 练习空间。再次加入打开已有实例为固定示意。
2. 概览切换作者移除/自动更新后的状态；历史显示当时版本与结果。
3. 保存冲突 → 刷新本题前显示可复制的当前输入 → 确认读取最新记录；不会静默丢弃输入。
4. 本轮得分 → 完成确认 → 已完成 → 冻结历史。待评分时禁用完成。
5. AI 与大纲切换、拉伸侧栏；AI 可发送纯文本追问，追加固定回复。切题演示对话隔离，关闭再打开仍为共用区域。
6. 我的分享 → 上传新版本 → 选择演示附件、确认权利 → 审核中。旧公开版继续使用；删除先提示保留用户实例和历史。

## 复用来源与范围

设计依据：`docs/design-ui-inventory` @ `ecc571b6c2124f5503a338c3e31b150f28fdae7b`，已阅读任务指定的需求、页面清单、共享更新、API、ADR-006。该固定版本见 [GitHub](https://github.com/lear111/quizforge-v2/tree/ecc571b6c2124f5503a338c3e31b150f28fdae7b/docs)。

分支从 main @ `e61c0049ad2150b0a78f59298c9bc326bec1cd1e` 创建，PR 只增加 prototypes，不携带设计分支的文档提交。

`assets/upstream/` 八份旧版视觉资源按字节复制；[UPSTREAM.json](UPSTREAM.json) 记录仓库、完整提交、路径、SHA-256。模板复用这些资源对应的现有类名和页面结构；新增的原型按钮、假数据状态与 AI 排版在 prototype.css / reuse.css 中。没有加载旧业务脚本、账户代码、播放器或 iframe。旧仓库只读。

本机网络设置、开发者设置、本机模型配置、题目编辑器及拓展示例不在原型中。题卡是明确标注的 HTML 占位；画笔、真实截图、媒体、移动端软键盘等未实现。

## 核对与验证

- [DATA_CONTRACT.md](DATA_CONTRACT.md)：逐项显示数据与接口来源。
- [GAPS.md](GAPS.md)：接口缺口、文档矛盾与评审覆盖项。
- [VALIDATION.md](VALIDATION.md)：实际检查结果和验收边界。
- `tools/build-pages.mjs`：生成 16 个入口和目录。
- `node prototypes/tools/check.mjs`：链接、脚本边界及上游哈希检查。
- `node prototypes/tools/interactions.mjs --jsdom-from <已安装 jsdom 的 package.json>`：可选 DOM 场景检查，不安装依赖。

原型检查不等于真实后端、AI、触控笔、手机设备或生产验收。
