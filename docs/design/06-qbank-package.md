# 题库包格式 4

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-28
> **依据：** [需求 v1.3 C-01、FR-09](../requirements/01-requirements.md)，[遗留契约 C-01～C-04](../legacy/01-contracts.md)，[数据库设计](01-database.md)，[题库共享与更新](04-bank-sharing-and-updates.md)
> **参照：** 旧格式 3 的规则（`quizforge_web/core/docs/reference/LIBRARY_PACKAGES.md`、`QUESTION_BANK_FORMAT.md`、`LISTENING.md`，`core/server/host/library/library-archive.js`、`directories.js`、`library-package.js` @ `4522bb8`）

本文定义 V2 起使用的 `.qbank` 题库包格式 4 与题库文件格式 2。它取代格式 3（需求 v1.3）：V2 只导入格式 4；Windows / 本地版同步改为导出格式 4。**拓展接口不变**（manifest、规则钩子、Schema、Bridge，C-02、C-03）。

---

## 1. 设计原则

1. **包是本地目录的直接拷贝。** 导出时把本地的题库分组目录和拓展分组目录直接复制进包，导入时直接复制出来，不改写任何文件内容、不转换路径。这条原则决定了目录结构与格式 3 保持一致。
2. **包内只有内容，没有用户数据。** 不含作答、草稿、历史、设置、密钥，也不含宿主生成的身份文件。
3. **严格校验，不猜测。** 不认识的字段、文件类型、路径一律拒绝并给出明确原因，不自动修正。
4. **与 V2 的存储方式解耦。** V2 不按目录存储（内容寻址文件存储 + 数据库，ADR-007），目录结构只影响包的交换，不影响 V2 内部设计。

## 2. 与格式 3 的差异

| 项目 | 处理 |
| --- | --- |
| 目录结构 `question-banks/<组>/<题库>/`、`extensions/<组>/<拓展>/` | 保留 |
| 拓展绑定 `"extension":{"path":"extensions/<组>/<拓展>"}` | 保留 |
| 整组包与单题库包（`scope:"bank"`） | 保留 |
| `features` 四个布尔开关 | 保留 |
| 包格式版本 | 3 → **4** |
| 题库文件格式版本 | 1 → **2**，且必须显式写出 |
| 听力音频 | 从题库的 `media/` **移入 `assets/`**；`media/` 不再允许 |
| 资源文件命名 | 图片不再要求 `<sha256>.<扩展名>`，**图片与音频都可自由命名**；引用方式不变（见第 6.3 节） |
| 资源类型 | 改为由**资源类型规则表**（第 6 节）统一定义 |
| 大小上限 | 上传 64 → **128 MiB**，解压 128 → **256 MiB**（第 7 节） |
| 旧格式兼容分支 | **删除**：`{id,version}` 旧绑定、分组根下的独立 JSON 题库、`development.json`、根平铺拓展等升级提示均不再需要 |

## 3. 包结构

```text
quizforge-package.json
question-banks/<组>/
├── <题库 A>/
│   ├── bank.json
│   └── assets/                      图片、音频等资源（可选）
└── <题库 B>/
    └── bank.json
extensions/<组>/
├── <拓展 X>/
│   ├── manifest.json
│   └── …                            manifest 声明的文件（拓展接口不变）
└── <拓展 Y>/
    └── …
```

### 3.1 格式标识 `quizforge-package.json`

```json
{"format":"quizforge-bank-group","formatVersion":4,"group":"考研英语"}
```

| 字段 | 约束 |
| --- | --- |
| `format` | 必须为 `"quizforge-bank-group"` |
| `formatVersion` | 必须为整数 `4`。其他值返回 422 `UNSUPPORTED_LIBRARY_ARCHIVE`；为 `3` 时提示“旧版题库包，请用新版客户端重新导出” |
| `group` | 必须与 `question-banks/` 和 `extensions/` 下唯一的分组目录名相同 |
| `scope` | 可选，`"group"`（默认）或 `"bank"`；其他值拒绝 |

不允许出现其他字段。文件不超过 256 KiB，UTF-8 JSON。

### 3.2 目录规则

| 规则 | 说明 |
| --- | --- |
| 顶层 | 只允许 `quizforge-package.json`（文件）、`question-banks/`、`extensions/` |
| 一个分组 | `question-banks/` 与 `extensions/` 下都**恰好一个**分组目录，名称等于 `group` |
| 题库目录 | `question-banks/<组>/<题库>/` 下**只允许** `bank.json`（必须有）与 `assets/` |
| `assets/` | 只允许文件，**不允许子目录**；每个文件必须匹配第 6 节的已启用资源类型 |
| 拓展目录 | `extensions/<组>/<拓展>/` 下必须有 `manifest.json`；其余文件规则与格式 3 相同：以 `.` 开头的路径、`node_modules`、`target` 等私有路径，除非在 manifest 中声明，否则拒绝 |
| 宿主文件 | 包内出现 `.instance.json`、`.bundle.json`、`development.json` 时拒绝 |
| 数量 | 题库 1～1000 本，拓展 1～200 个 |
| 单题库包 | `scope:"bank"` 时恰好一本题库，只携带它用到的拓展 |

通用路径规则（深度、`.`、`..`、禁用字符、保留名、大小写不敏感的重复）由 `QbankArchiveReader` 负责，与格式 3 相同。

## 4. 题库文件 `bank.json`（格式 2）

```json
{
  "formatVersion": 2,
  "title": "1997年真题",
  "extension": {"path": "extensions/考研英语/完形填空"},
  "features": {"whiteboard": true, "exam": true},
  "listening": {
    "materials": [{"id": "part-a", "title": "Part A", "file": "assets/part-a.mp3"}],
    "examMaterialId": "part-a"
  },
  "questions": [{
    "id": "q1",
    "title": "第 1 题",
    "outline": {"level": "question", "label": "1"},
    "listening": {"materialId": "part-a"},
    "data": { }
  }]
}
```

| 字段 | 约束 |
| --- | --- |
| `formatVersion` | **必填**，整数 `2` |
| `id`、`version` | 可选，作者元数据，不参与去重或更新判断 |
| `title` | 可选，元数据；**显示名称取题库文件夹名**（与格式 3 相同） |
| `description` | 可选字符串 |
| `extension` | 可选的默认绑定 `{path, id?}`，逐题绑定优先 |
| `features` | 可选，只允许 `editing`、`whiteboard`、`history`、`exam` 四个布尔字段，省略为 `true`。在线版：`whiteboard`、`exam` 生效；`editing` 不使用；`history:false` 忽略（统一保存历史） |
| `listening` | 可选，见 4.2 |
| `questions` | 1～10000 项，按题序排列 |

每道题：

| 字段 | 约束 |
| --- | --- |
| `id` | 匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$`，同一题库内唯一（区分大小写） |
| `title` | 非空，最多 300 字符 |
| `extension` | 可选的逐题绑定；**每道题必须有有效绑定**（逐题或默认） |
| `outline` | 可选，`{level:"question",label}` 或 `{level:"parts",items:[{id,label}]}`（1～100 项），规则与格式 3 相同 |
| `listening` | 可选，`{materialId}`，必须指向本题库声明的材料 |
| `data` | 由所绑定拓展的 `questionSchema` 与规则校验，宿主不解析内部字段 |

`bank.json` 不超过 8 MiB。

### 4.1 拓展绑定

- `path` 必须是 `extensions/<组>/<拓展>`，且该拓展**在本包内**；不按 ID 或名称猜测。
- 可选 `id` 存在时，必须与该拓展 `manifest.json` 的 `id` 相同。

### 4.2 听力材料

| 字段 | 约束 |
| --- | --- |
| `materials` | 1～50 项 |
| `materials[].id` | 1～80 位字母、数字、下划线或连字符，题库内唯一 |
| `materials[].title` | 1～160 字符 |
| `materials[].file` | 必须是 `assets/<文件名>`，且该文件属于**音频**类型 |
| `examMaterialId` | 可选，必须是已声明的材料 ID |

## 5. 拓展

拓展接口（`manifest.json` 字段、`requiresApi`、规则钩子、Schema、页面与 Bridge）**完全不变**，以 C-02、C-03 为准。

与包格式相关的只有两点：

- 拓展的示例题库（`manifest.examples` 指向的文件）使用**题库文件格式 2**，其中的绑定必须准确指向所属拓展自己（`extensions/<组>/<该拓展>`）。在线版保留示例文件但不展示、不可练习（需求 FR-03）。
- 审核摘要（C-04）的算法不变。示例中的绑定写法（`{path}`）与格式 3 相同，规范化步骤无需修改。

## 6. 资源类型规则表

题库 `assets/` 中的每个文件，按下表确定类型并校验。**规则以本表为唯一依据**，代码中对应一张同样的表（例如 `ResourceType` 枚举），新增类型只需增加一行。

| 类型 | 扩展名（不区分大小写） | 文件头识别 | 单文件上限 | 引用方式 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 图片 | `png` `jpg` `jpeg` `webp` `gif` | PNG 签名、JPEG `FF D8 FF`、`RIFF….WEBP`、`GIF87a`/`GIF89a` | 4 MiB | 富文本图片节点 `attrs.assetId`（内容哈希） | 启用 |
| 音频 | `mp3` `m4a` | MP3：`ID3` 或帧同步；M4A：`ftyp` 盒 | 32 MiB | `listening.materials[].file`（路径） | 启用 |
| 视频 | `mp4` `webm` | `ftyp` 盒；EBML 头 | 待定 | 待定 | **预留，未启用** |

### 6.1 通用规则

1. 文件必须匹配一个**已启用**的类型，否则返回 422 `UNSUPPORTED_RESOURCE_TYPE`（“不支持的资源类型：文件名”）。
2. 扩展名与文件头**都要**匹配同一类型，不一致即拒绝（例如实际是可执行文件的 `.png`）。
3. 文件名自由，但须符合通用路径规则；同一题库内不能重名（大小写不敏感）。
4. 内容相同、名称不同的文件允许存在，V2 中只存一份。

### 6.2 为什么视频暂不启用

视频文件大，上传、存储和带进度拖动的流式播放（HTTP Range，需求 FR-06）都需要单独设计；放进包内可能使上传上限达到数百 MB。需要时另提需求变更，本表已预留位置。

### 6.3 图片引用：名称自由，按内容哈希引用

富文本按内容哈希（`assetId` = 图片内容的 SHA-256）引用图片，这是拓展接口的一部分（C-02，`QF.content`、`QF.resources`、答题区快照均依赖它），**保持不变**。因此：

- 导入时计算 `assets/` 中每张图片的 SHA-256，建立“哈希 → 文件”的索引；
- 题目数据中出现的每个 `assetId` 都必须能在索引中找到，否则拒绝（“引用了不存在的图片”）；
- 本地版加载题库时建立同样的索引。

文件名只是给制作者看的，不参与引用。

## 7. 大小与数量上限

| 项目 | 上限 | 由谁检查 |
| --- | --- | --- |
| 上传的 `.qbank` | 128 MiB | 上传接口（边接收边计数） |
| 解压后总量 | 256 MiB | `QbankArchiveReader` |
| 条目数 | 10000 | `QbankArchiveReader` |
| 路径深度 | 32 段 | `QbankArchiveReader` |
| 任意单个文件 | 32 MiB（表中最大的类型上限） | `QbankArchiveReader` |
| 资源文件 | 按第 6 节各类型的上限 | 包结构解析 |
| `bank.json`、拓展文件 | 8 MiB | 包结构解析 |
| `quizforge-package.json`、`manifest.json`、`editor.json` | 256 KiB | 包结构解析 |

两层分工：`QbankArchiveReader` 只做通用的安全上限（防解压炸弹），不了解文件类型；按类型的上限在识别类型之后检查。

## 8. 校验顺序与错误码

按“便宜的检查在前”的顺序执行，任一步失败立即停止，什么都不保存：

| 步骤 | 检查 | 失败时 |
| --- | --- | --- |
| 1 | 上传大小 | 413 `LIBRARY_ARCHIVE_LIMIT` |
| 2 | 安全解压（`QbankArchiveReader`） | 413 `LIBRARY_ARCHIVE_LIMIT` / 422 `INVALID_LIBRARY_ARCHIVE` |
| 3 | 格式标识 | 422 `UNSUPPORTED_LIBRARY_ARCHIVE` / 422 `INVALID_LIBRARY_ARCHIVE` |
| 4 | 目录规则（3.2） | 422 `INVALID_LIBRARY_ARCHIVE` |
| 5 | 资源类型与大小（第 6 节） | 422 `UNSUPPORTED_RESOURCE_TYPE` / 413 `LIBRARY_ARCHIVE_LIMIT` |
| 6 | `bank.json` 结构、绑定、听力、图片引用（第 4 节、6.3） | 422 `INVALID_QUESTION_BANK`，消息指明题库与题目 ID |
| 7 | 拓展 manifest 与 API 版本声明 | 422 `INVALID_EXTENSION` |
| 8 | 计算拓展审核摘要，查白名单（需求 FR-11） | 不失败；未审核时题库为“审核中” |
| 9 | rule-runner：`validateBank`、`outlineBatch`、`scoreBatch` | 按 rule-runner 协议第 8 节 |

## 9. 在 V2 中的存储

不改写任何文件（原则 1）：

| 包内内容 | 存到哪里 |
| --- | --- |
| 每本题库的 `bank.json` | `content_bank` 一行 + `content_question` 每题一行；题目绑定换成拓展审核摘要 |
| 每本题库的 `assets/` | 文件进 BlobStore；`content_bank_asset` 记录“包内路径 → 内容哈希” |
| 每个拓展的全部文件 | 文件进 BlobStore；`content_extension_file` 记录“路径 → 内容哈希”，`content_extension_package` 一行 |
| 整个 `.qbank` 原始文件 | 题库广场的版本保留原文件，供下载时逐字节返回（需求 FR-11） |

## 10. 对其他组件的影响

| 组件 | 影响 |
| --- | --- |
| `QbankArchiveReader`（M2.2） | 顶层白名单不变；`ArchiveLimits.DEFAULT` 改为单文件 32 MiB、总量 256 MiB |
| 包结构解析（M2.6a） | 按本文实现 |
| `ExtensionDigest`（M2.4） | 无需修改 |
| Windows / 本地版（不在 V2 范围） | 导出、导入改为格式 4；听力音频改放 `assets/`；按 6.3 建立图片哈希索引 |
| Android / Windows 客户端的固定版本导入（C-05） | 客户端现在校验下载大小不超过 64 MiB，须同步提高到 128 MiB |

## 11. 待定事项

| 编号 | 事项 |
| --- | --- |
| PK-1 | 视频资源的上限、引用方式与播放（第 6.2 节） |
| PK-2 | `assets/` 中未被引用的资源：目前照样存储（与“直接拷贝”一致），是否需要提示制作者 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-28 | 初稿 |
