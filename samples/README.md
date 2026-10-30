# 格式 4 读题验收包

- `basic-types.qbank`：整组包，五本题库，每本保留一个示例题（单选、多选、判断、简答、答题区简答）。单选选取含图片的示例，验证真实图片链路。
- `single-choice.qbank`：单题库包，供接口测试使用。
- 来源：[quizforge_web](https://github.com/lear111/quizforge_web) 提交 `4522bb81c2126a699fcf72ce61d074dbc1264161` 的 `extensions/基础题型/`。代码版权与 MIT 许可见 [上游许可](../frontend/LICENSE.upstream)。

转换仅涉及包格式：添加格式 4 标识；题库与拓展示例设为格式 2，各自写入固定的小写 UUID；绑定使用包内拓展路径；为实际题库复制示例图片。题目 data、规则、页面和编辑资源沿用固定提交的字节；未在规则或页面中删除标准答案作为防泄露手段。实际读题必须通过 rule-runner 的 project。

`build.py` 的 `BANK_IDS` / `EXAMPLE_IDS` 写死十个互不相同的 UUID；重新生成不改变身份，整组包和单题库包中的同一本题库使用相同 UUID。拓展示例 ID 变化会按既有算法改变审核摘要，因此重新导入的样例仍须审核，不复用旧摘要的批准记录。

重新生成（Python 标准库；上游 Git 仓库必须含该提交）：

```powershell
python samples/build.py C:/path/to/quizforge_web
Get-FileHash samples/*.qbank -Algorithm SHA256
```

ZIP 内时间固定为 2026-10-30，条目按路径排序。当前生成结果：

| 文件 | SHA-256 |
| --- | --- |
| basic-types.qbank | 465c8fbd683760361dad6c01b1e68866226dfc913d777d53666bcc7d37c77988 |
| single-choice.qbank | 1c2ec4aeda7a0dad1102b20ab9d7cb505a21977dc743a4db4ba0be7f2c65dd23 |

导入不会自动批准拓展。真实使用仍需审核；自动浏览器测试只在显式指定的独立 `qf-*` Compose project 内，给本次导入的五个样例摘要设置测试白名单。该过程不是新增审核 API，也不能用于真实上传内容。
