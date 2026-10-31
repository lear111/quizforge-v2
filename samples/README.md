# 格式 4 读题验收包

- `basic-types.qbank`：整组包，五本题库，每本显示一个示例题，另含一个未被大纲引用的题（单选、多选、判断、简答、答题区简答）。单选选取含图片的示例，验证真实图片链路。
- `single-choice.qbank`：单题库包，供接口测试使用。
- `central-outline.qbank`：单题库包，3 道可见题与 1 道未引用题；大纲顺序不同于 questions 顺序，包含相邻两个同名分组、无标题分组、label、parts 和省略 label/parts。
- `loadtest-300.qbank`：压测专用单题库包，固定 UUID `60000000-0000-4000-8000-000000000300`；单选、多选、判断各 100 道交错排列，300 道均在顶层 outline 中，分成 6 个作者分组。包标识格式 4，题库格式 2，符合集中大纲 v0.7；不引用图片。题目由固定上游示例复制并改写题干、选项 ID 与判断答案，拓展代码/manifest 不变。
- 来源：[quizforge_web](https://github.com/lear111/quizforge_web) 提交 `4522bb81c2126a699fcf72ce61d074dbc1264161` 的 `extensions/基础题型/`。代码版权与 MIT 许可见 [上游许可](../frontend/LICENSE.upstream)。

转换仅涉及包格式：添加格式 4 标识；题库与拓展示例设为格式 2，各自写入固定的小写 UUID；绑定使用包内拓展路径；为实际题库复制示例图片；将逐题 outline 提升至顶层。集中大纲专用包使用原本声明 outline-items 的简答拓展，parts 使用题目中的真实小题 ID；规则脚本、manifest 与页面不变。题目 data、规则、页面和编辑资源沿用固定提交的字节；未在规则或页面中删除标准答案作为防泄露手段。实际读题必须通过 rule-runner 的 project。

`build.py` 的 `BANK_IDS` / `EXAMPLE_IDS` 写死十个互不相同的 UUID；重新生成不改变身份，整组包和单题库包中的同一本题库使用相同 UUID。拓展示例 ID 变化会按既有算法改变审核摘要，因此重新导入的样例仍须审核，不复用旧摘要的批准记录。

重新生成（Python 标准库；上游 Git 仓库必须含该提交）：

```powershell
python samples/build.py C:/path/to/quizforge_web
Get-FileHash samples/*.qbank -Algorithm SHA256
```

ZIP 内时间固定为 2026-10-30，条目按路径排序。当前生成结果：

| 文件 | SHA-256 |
| --- | --- |
| basic-types.qbank | 808ae8e05d3acc0f9d283f94cf75ee9cb17e573e640128f739efff6ab94fee32 |
| central-outline.qbank | 80d1c2c9d8c8847e3d904e491d8fcf7ce3b31a4a14e694d64a09ec6c15f21050 |
| single-choice.qbank | 9509dc5ec8f77c13ae53262db5a594d985bb723865988de2616aa9dfeff255e0 |
| loadtest-300.qbank | cb8a9e3d7faae3963dfbb86936ef9e1dee47371098c054065b73520d9f30e8dc |

导入不会自动批准拓展。真实使用仍需审核；自动浏览器测试只在显式指定的独立 `qf-*` Compose project 内，给本次导入的五个样例摘要设置测试白名单。该过程不是新增审核 API，也不能用于真实上传内容。
