# 格式 4 读题验收包

- `basic-types.qbank`：整组包，五本题库，每本保留一个示例题（单选、多选、判断、简答、答题区简答）。单选选取含图片的示例，验证真实图片链路。
- `single-choice.qbank`：单题库包，供接口测试使用。
- 来源：[quizforge_web](https://github.com/lear111/quizforge_web) 提交 `4522bb81c2126a699fcf72ce61d074dbc1264161` 的 `extensions/基础题型/`。代码版权与 MIT 许可见 [上游许可](../frontend/LICENSE.upstream)。

转换仅涉及包格式：添加格式 4 标识；题库与拓展示例设为格式 2；绑定使用包内拓展路径；为实际题库复制示例图片。题目 data、规则、页面和编辑资源沿用固定提交的字节；未在规则或页面中删除标准答案作为防泄露手段。实际读题必须通过 rule-runner 的 project。

重新生成（Python 标准库；上游 Git 仓库必须含该提交）：

```powershell
python samples/build.py C:/path/to/quizforge_web
Get-FileHash samples/*.qbank -Algorithm SHA256
```

ZIP 内时间固定为 2026-10-30，条目按路径排序。当前生成结果：

| 文件 | SHA-256 |
| --- | --- |
| basic-types.qbank | 77eab5663b85cc2ab46b23cee58cfeaa5f6c19dacefd768005b4641cb8fa8428 |
| single-choice.qbank | f859f3147e37114177db51447a71f3574bfa01b308e94ee9dfd7d76482d30c38 |

导入不会自动批准拓展。真实使用仍需审核；自动浏览器测试只在显式指定的独立 `qf-*` Compose project 内，给本次导入的五个样例摘要设置测试白名单。该过程不是新增审核 API，也不能用于真实上传内容。
