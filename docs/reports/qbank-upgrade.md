# 格式 3 → 4 转换与考研英语组件兼容性报告

## 1. 改动范围与转换契约

基于 main `4b7e9a9`，分支 `feat/qbank-upgrade-tool`。没有修改 V2 生产代码或作者旧版目录。

| 文件 | 用途 |
| --- | --- |
| `tools/qbank-upgrade/upgrade.py` | Python 标准库 CLI；受限 ZIP 读取、结构转换、位置明确的拒绝、原子且不覆盖地发布输出、生成无题目正文的报告 |
| `tools/qbank-upgrade/test_upgrade.py` | 30 个合成包单元测试，不读取真实题库 |
| `tools/qbank-upgrade/README.md` | 命令、转换范围、UUID 与失败语义、限制 |
| `.gitignore` / `.dockerignore` | 私人输入、输出、截图、登录态既不提交，也不进入 Docker 构建上下文 |
| `.github/workflows/ci.yml` | 在现有 backend job 内运行 Python 测试，不增加第五个 job |
| 本报告 | 本地验收、全部组件与能力声明、未开放功能及兼容性差异 |

包版本 3 → 4，题库文件版本 1 → 2；逐题 outline 汇总为顶层 outline，所有题保留且顺序不变。相邻且拓展绑定路径相同的题成组，组名取 manifest.name；不认识的作者分组字段拒绝，不推断。媒体文件仅移动 MP3/M4A 原字节并改写宿主听力引用，不转码、不修改拓展 JS。

UUID 定义为 `UUIDv5(NAMESPACE_URL, "qf-qbank-upgrade-v1:" + 原ZIP字节的SHA256 + ":" + 原ZIP条目路径)`；每本题库及示例各自生成。相同输入字节产生相同 UUID、相同输出 ZIP 字节。重新压缩或修改原包可能改变 UUID，此工具不承担跨版本题库身份管理。opaque data 的数字 token（含 -0、1.0、1e21）及 JSON 值保留，不通过 Python float 重写题目。

按作者确认，允许升级 manifest.examples 指向的 JSON；其余拓展文件逐字保留。8 个被修改的示例均为 `extensions/考研英语/<题型名>/examples/bank.json`，题型名为下表中的八项。修改仅限版本、UUID、大纲及必要音频路径，**会改变拓展审核摘要，导入后需要重新审核**。

## 2. 自动测试与真实导入

本地执行：

```powershell
python -B -m unittest discover -s tools/qbank-upgrade -p 'test_*.py'
```

结果：30 个测试，0 失败，2.752 秒。所有输入由测试现场构造。主要覆盖对应如下，完整方法名见测试文件：

| 覆盖 | 测试方法（`test_` 前缀） |
| --- | --- |
| 包/题库版本、组/单题库、拓展文件不变 | `group_versions_ids_outline_and_extension_bytes`、`single_bank_scope_preserved`、`omitted_bank_version_is_documented_legacy_one` |
| 确定性、文件间 UUID 唯一、输入变化 | `same_input_has_same_uuid_and_identical_output_zip`、`distinct_input_changes_uuid_and_multiple_banks_are_unique` |
| 题序、交替拓展、label/parts/省略大纲 | `parts_default_and_alternating_extension_groups_keep_question_order`、`outline_invalid_shape_label_parts_capability_or_duplicate_reject` |
| 音频移动、示例例外、资源引用 | `media_audio_moves_and_only_host_file_reference_changes`、`example_audio_uses_same_conversion_and_preserves_audio_bytes`、`images_keep_hashes_names_and_bytes` |
| 数字、Unicode、转义、未知字段不丢弃 | `number_tokens_unicode_and_escapes_are_not_lost`、`unknown_fields_and_group_metadata_reject_with_location` |
| 不能转换的绑定/媒体/缺文件/非法 JSON | `wrong_bindings_missing_files_and_examples_owner_reject`、`media_collision_missing_reference_and_unsupported_types_reject`、`invalid_utf8_duplicate_json_key_nonfinite_or_trailing_json_reject` |
| 路径、重复、空目录、大小与条目上限 | `unsafe_duplicate_private_and_nested_paths_reject`、`empty_directories_are_ignored_and_not_counted_as_groups`、`small_custom_limits_bound_file_count_and_total`、`empty_zip_and_excessive_entry_count_reject` |
| CRC、加密、非法 UTF-8 / 未声明的文件名编码、链接 | `crc_corruption_and_non_zip_reject_without_partial_output`、`encryption_and_invalid_utf8_zip_names_are_controlled_errors`、`symlink_zip_entry_rejects` |
| 不覆盖、发布竞争、临时文件清理、CLI 报告与退出码 | `no_overwrite_even_at_atomic_publish_race`、`cli_report_and_failure_exit_codes` |

本机源资料是作者指定目录中的“考研英语”分组。由于提供的是安装目录而不是现成 ZIP，先按旧 exportPackage 的布局组装本地格式 3 输入：题库文件和资源、实际绑定的拓展文件；不包含宿主身份/开发配置。原目录未修改。

| 真实文件检查 | 结果 |
| --- | --- |
| 输入 ZIP | 235 条目，3,953,140 字节；47 本题库、412 道题、8 个拓展 |
| 转换审计 | 412 道题除移出的 outline 外，逐题字段和值与输入相同；题序相同 |
| 资源 | 68 个资源逐字相同；本组没有 media 音频（迁移由合成测试覆盖） |
| 拓展 | 111 个非示例文件逐字相同；8 个 examples JSON 获准升级 |
| 重复生成 | 最终 CLI 输出与实际导入的包 SHA-256 完全相同 |
| 本地真实导入 | `POST /api/library/import` → 200，一次响应创建 47 个题库实例 |

Compose 项目 `qf-qbank-upgrade`，地址 `http://localhost:8103`，真实 MySQL / rule-runner / Nginx / app。使用新注册的本地测试用户；只在该项目数据库为本次导入的拓展摘要插入审核白名单，才能进行练习验收。这不代表产品已有自动审核功能。没有连接远程部署或 AI 服务。

## 3. 八种题型逐项验收

下表 C 表示准确的十项声明：`practice`、`editor`、`editor-drafts`、`score`、`outline-items`、`lifecycle`、`richtext`、`resources`、`answer-state`、`appearance`。M 表示 `manual-review`、`ai-grading`、`answer-areas`。

| 拓展名 | manifest.version | requiresApi | 完整能力集合 | 富文本需求 | 本机实际打开 / 截图 |
| --- | --- | --- | --- | --- | --- |
| 完形填空 | 1.0.1 | major=1, minMinor=4 | C | R1 | 200，显示正常，`1-type.png` |
| 阅读理解 | 1.0.1 | 1 / 4 | C | R1 | 200，显示正常，`2-type.png` |
| 分句翻译（答题区） | 1.0.3 | 1 / 5 | C + M | R1 | 200，5 个答题区；输入、草稿、提交均成功；`3-type.png`、`3-submitted.png` |
| 简答题（答题区） | 1.0.5 | 1 / 5 | C + M | R2 | 200，1 个答题区，正文图片正常；输入、草稿、提交均成功；`4-type.png`、`4-submitted.png` |
| 阅读选填 | 1.0.1 | 1 / 4 | C | R1 | 200，显示正常，`5-type.png` |
| 阅读匹配 | 1.0.1 | 1 / 4 | C | R1 | 200，显示正常，`6-type.png` |
| 全文翻译（答题区） | 1.0.3 | 1 / 5 | (C 去除 outline-items) + M | R3 | 200，1 个答题区；输入、草稿、提交均成功；`7-type.png`、`7-submitted.png` |
| 段落排序 | 1.0.1 | 1 / 4 | C | R1 | 200，显示正常，`8-type.png` |

每种选取一题，通过真实练习空间的题库与大纲按钮打开，核对 iframe 中的题型脚本和题目投影确实对应所选项，然后截图并逐张检查。覆盖八种题型的代表题，**不等于逐一浏览全部 412 题**。可见损坏图片数 0，`.qfrt-image-missing` 数 0，页面异常数 0。答题区的隐藏 snapshot img 在尚无手写快照时没有 src，不属于正文图片丢失。

三个答题区都填写测试文本后，真实 HTTP 草稿保存与提交各返回 200，扩展返回 gradingStatus=pending；宿主没有伪造评分。输入/提交证据保留在本机 `browser-result.json`，匹配脚本、题目与图片检查在 `recapture-result.json`。

所有截图位于 **git 忽略目录 `tools/qbank-upgrade/local/screenshots/`**，不附到仓库或 PR，避免公开真实题目；输入、输出、JSON 报告及测试登录态也仅在 local/ 下保存。PR 仅引用本报告中的元数据。

## 4. 富文本、答题区与宿主组件

八个 manifest 都没有 dependencies 固定组件版本数组；它们通过 requiresRichText 与 Extension API 能力协商组件。不能把 minMinor 误写成 npm/组件版本。

| 声明 | 精确内容 | V2 支持与本次证据 |
| --- | --- | --- |
| R1（六种） | major=1, minMinor=0, documentFormat=1, documentProfile=advanced-v1；basic-formatting / images / image-resize | 服务 API 1.1 / 文档格式 1 满足；解析到 quizforge.richtext **1.1.3**，SDK 请求 200 |
| R2（简答题答题区） | 同 R1，加 advanced-formatting / tables / math | provider 声明支持六项，成功载入并显示真实图文；未逐个操作数学、表格编辑器 |
| R3（全文翻译答题区） | major=1, minMinor=1, documentFormat=1, advanced-v1；basic-formatting / images / image-resize / camera-capture | 服务与 provider 均满足版本及声明；拍照/上传入口可见，但新资源写入尚未开放，见第 6 节 |
| 富文本七项能力 | basic-formatting、images、advanced-formatting、tables、math、image-resize、camera-capture | `frontend/shared/richtext/service.json` 的 advanced-v1 全部声明支持，provider=quizforge.richtext 1.1.3；宿主写资源能力仍受接口限制 |
| 答题区 | 三个答题区拓展声明 API 1.5 + answer-areas；practice.js 实际要求 provider >=1.4.1 | V2 统一提供 **quizforge.answer-area 1.4.1**，SDK 请求 200，文本输入与提交实测成功 |
| 答题区内部依赖（不是 manifest 独立声明） | model 1.4.0、legacy component 1.2.0、shared component 1.4.1、ink renderer 1.0.0 | 由 `frontend/scripts/build-sdk.mjs` 打为 provider 1.4.1；是否启用手写还取决于宿主能力 |

### 旧宿主是否也统一提供答题区 1.4.1

**对旧版基准 `quizforge_web@4522bb8`，答案是“是”。** `core/server/host/extensions/answer-area-component.js:answerAreaComponent()` 固定返回 version=1.4.1，内部组合上述 model、legacy、shared、ink。`extension-packages.js:snapshotPage()` 发现 answerAreasDeclared 就调用它，并没有根据拓展选取 1.0.0～1.4.1。`config.sharedInk` 选择的是 provider 内的实现，不是选择对外版本。历史版本目录仍存在，不等于宿主会按拓展固定版本运行。V2 保持这一基准行为。

**作者本机工作副本另有未提交的 1.4.2 改动，不能与基准混为一谈。** 当前旧宿主 answer-area-component.js 指向本地未跟踪的 1.4.2/component.js，增加 `paperStyle:"ruled"`。三种答题区的本机 practice.js 均先检查 >=1.4.1，只有 provider >=1.4.2 才传 ruled；因此在 V2 1.4.1 下仍能输入、提交，但退回空白纸，**不显示本机实验版本的横线纸样式**。未将 1.4.2 称为正式发布版本，未更改任一组件。出处：三个拓展 practice.js 中 QF.answerAreas.mount 的版本判断（全文翻译约第 188 行、分句翻译约第 135 行、简答约第 161 行），以及上述旧宿主文件。

V2 版本选择出处：`ContentPages.java:page()` 的 answerAreaVersion=1.4.1、`SdkRegistry.java` 的 provider 白名单、`bridge/page-assets.js:prepareExtensionAssets()` 与 `bridge/frame.js:mountExtension()`。frame 中接受历史版本号的列表只是完整性校验，不能解释为运行时提供所有旧版本。

## 5. Extension API 能力逐项支持情况

V2 `LegacyExtensionRules` 接受 API 1.0～1.5 并校验能力门槛；前端 api-v1 桥接实际承载以下能力。**manifest 可以通过校验，不代表该能力对应的所有在线功能已经完成。**

| 本组声明的能力（并集 13 项） | V2 当前情况 |
| --- | --- |
| practice | 支持练习读取、作答、保存、提交；八题真实页面与三个答题区写操作已验收 |
| editor | 声明被识别；在线版题目编辑入口按 ADR-006 移除，本次不支持作者编辑流程 |
| editor-drafts | 协议可识别；在线编辑器及编辑草稿流程未开放，不能宣称端到端可用 |
| score | 真实 rule-runner 执行导入满分计算与提交；三个答题区按拓展语义 pending，非宿主失败 |
| outline-items | 支持小题大纲和状态桥接，集中大纲按转换后的 label/parts 展示；全文翻译未声明此项 |
| lifecycle | 桥接与题卡挂载可用，八种题型正常打开、切换，无 pageerror |
| richtext | 服务 API 1.1、文档格式 1、advanced-v1/provider 1.1.3 满足全部声明；图文显示已验收 |
| resources | 已有题库图片读取可用；新插图/拍照上传的 POST /api/resources 未实现，404 后友好提示 |
| answer-state | 桥接识别；draft 请求额外 answerState 尚无持久化通路（设计 07 已记录），不能承诺全部小题 UI 状态刷新后恢复 |
| appearance | API 桥接与主题显示可用；本次截图为真实宿主主题，未逐个验证全部外观设置 |
| manual-review | 当前生产代码已提供 review 并允许已提交题人工评分；页面可显示人工评分控件。本任务未实际修改分数，不把控件可见当成独立接口验收 |
| ai-grading | 未开放。读题 canAiGrade=false；分句/全文翻译按钮禁用，简答题按钮隐藏。未调用 AI |
| answer-areas | provider 1.4.1 的文本答题、草稿、提交实测正常；白板/手写/截图入口目前受 canWhiteboard=false 限制，未开放 |

## 6. 发现而未修改的差异、边界

1. **插图写入未开放**：在真实简答答题区通过“上传图片”选择合成的 1px PNG，POST /api/resources 返回 404；页面显示“该功能尚未开放，或内容已不存在。”，无白屏/页面异常，截图 `unsupported-upload.png`，详细元数据在本机 controls-result.json。原题库已有图片读取与显示正常。
2. **手写/截图/AI 尚不可用**：canWhiteboard=false，宿主画笔与白板工具隐藏，截图工具随其未开放；没有强行解除能力开关、调用隐藏工具或宣称真实笔/相机验收。拍照是可见文件入口，未访问物理相机；其资源写通路与插图相同。AI 控件禁用或隐藏。这些是当前 V2 边界，未作兼容性修补。
3. **1.4.2 横线样式**：当前本机旧版实验组件可显示、V2 1.4.1 不显示；文本作答降级可用，原因见第 4 节。未修改拓展代码。
4. **answerState 持久化**：既有接口只保存 answer，不是此次转换工具引入的问题；不把“草稿保存 200”扩大为全部组件临时状态持久化成功。
5. **转换器不是宿主校验器**：不运行 AJV/JS，也不猜测未知格式。静态转换成功仍须真实 V2 导入确认 Schema、规则、SDK。只接受可明确转换的数据；遇到旧自定义字段、非法分组名、不支持资源则报告位置并拒绝，不静默丢弃。
6. **文件系统与身份边界**：原子无覆盖发布依赖目标文件系统硬链接支持；不支持会失败。UUID 与原 ZIP 字节绑定，重新打包可能产生新题库身份。没有尝试保留宿主私有实例身份。

CI 的四个 job 及精确运行链接记录在 PR 描述；本次不新增第五个 job。真实题库和浏览器验收只在本机执行。测试结束关闭本任务 Compose 项目，不删除卷或私人验收文件。
