# 旧题库包转换工具

Python 3.10+，只使用标准库；输入是 ZIP 容器的**格式 3** `.qbank`，输出是设计 06 v0.7 的**格式 4 / 题库文件格式 2**。不修改输入文件、不执行拓展代码、不连接服务器。

```powershell
python -B tools/qbank-upgrade/upgrade.py old.qbank upgraded.qbank --report conversion-report.json
python -B -m unittest discover -s tools/qbank-upgrade -p 'test_*.py'
```

输出包和报告路径必须不存在，父目录须已存在。成功返回 0，并在 stdout 输出 JSON 报告；失败返回 2，stderr 指出 ZIP 路径和字段位置。压缩包校验/转换失败不会发布半成品；先写同目录临时文件、fsync，再通过不覆盖的 hard link 发布，临时文件最终删除。目标文件系统需要支持硬链接（例如 NTFS、ext4）；不支持时明确失败，不回退覆盖。若只是报告写入失败，已生成的有效包保留，stderr 会明确说明。

## 转换规则

1. `quizforge-package.json.formatVersion`：3 → 4，保留 group / scope；整组及单题库均支持。目录和绑定必须已经是格式 3 的准确路径形式，不猜测旧 `{id,version}` 对应哪个拓展。
2. 题库 `formatVersion`：1 → 2。仅对题库文件沿用旧版“省略版本按 1”规则；包版本必须显式为整数 3。
3. 每本题库及每个示例生成小写 UUIDv5：`uuid.uuid5(uuid.NAMESPACE_URL, "qf-qbank-upgrade-v1:" + 原包SHA256 + ":" + 原ZIP文件路径)`。相同原包字节重复转换，UUID 和输出 ZIP 字节都相同；各文件的 UUID 不同。**重新打包即使内容相同也可能改变 UUID**，修改原包也会改变它；这不是跨版本题库身份管理工具。原 format-3 的 id 会被新 UUID 取代，其余作者元数据保留。
4. 题库 `media/<文件名>` 音频移到 `assets/<文件名>`，原字节不变，改写对应 `listening.materials[].file`；示例中的听力按同一规则处理。重名（含大小写差异）直接拒绝，不选择覆盖或改名。仅接受格式 4 启用的 MP3 / M4A，不对 WAV / OGG 或其他类型转码。
5. 按 questions 数组顺序，为**相邻且绑定路径相同**的题生成一组，组名取 manifest.name。不同拓展即使同名也不合并。显式逐题 `{level:"question",label}` → 顶层题目项 label，`{level:"parts",items}` → parts；省略 outline 的题仅写 `{question:id}`，保留导入时派生/自动编号语义。逐题 outline 随后删除，所有题均进入顶层大纲。
6. 公开格式 3 没有标准的作者分组字段（旧 QUESTION_BANK_FORMAT.md / SUBQUESTION_OUTLINE.md）；遇到不认识的分组/字段，报位置并拒绝，不推断其含义、不丢弃。非法/过长的组名、标签、小题及缺失绑定同样拒绝。
7. **经产品确认的唯一 JSON 例外**：升级 `manifest.examples` 指定的示例 JSON，其版本、UUID、大纲、必要的听力路径与题库规则一致；必要的示例音频迁移只改变路径、不改音频字节。其余拓展文件（manifest、JS、Schema、HTML、CSS、SDK 声明等）逐字保留。报告单独列出每个 examples 文件和音频迁移；**示例改变会导致拓展摘要变化，V2 导入后必须重新审核**。
8. 题目 data 不做结构推测/改写，图片 assetId 不变；数值 token 保留原写法，包括 `-0`、`1.0`、`1e21` 和大整数，避免 Python 浮点序列化损失精度。JSON 空白和字符串转义写法可变化，值不变。

工具检查 ZIP 路径、重复（忽略大小写）、链接/加密/CRC、UTF-8 JSON/重复键、格式结构、绑定、显式大纲、听力与图片引用、资源头和数量/大小上限；不实现 AJV 或 JavaScript 规则运行。**V2 的真实导入是最终校验**，包括 Schema、钩子和 SDK/provider 可用性；不宣称静态转换替代这些校验。

## 报告与私人数据

JSON 报告包含原/新包 SHA-256、UUID 算法、每本题库/示例的文件位置、新 UUID、题数/大纲组数、音频移动和引用改写位置、未修改拓展文件数、忽略的目录条目数与审核提醒；不包含题目正文、答案或 data。

作者本机验证使用的输入、输出、报告、测试登录态和截图全部放在 `tools/qbank-upgrade/local/`。该目录同时被 Git 和 Docker build context 忽略；不要强制 add，也不要把真实题库用于测试夹具或上传到 CI。

本次本地 Compose 验证及组件兼容性见 [验收报告](../../docs/reports/qbank-upgrade.md)。CI 在既有 backend job 中运行合成包单元测试，不读取任何真实题库，仍保持四个 job。
