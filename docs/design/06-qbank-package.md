# 题库包格式 4

> **文档版本：** v0.6
> **日期：** 2026-10-29
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

**目录规则只按文件判断，空目录一律忽略。** ZIP 中不一定有目录条目（很多工具只写文件条目），依赖目录条目会让同一个包因打包工具不同而得到不同结果；空目录也不携带内容，V2 不按目录存储。因此上表的规则都按“不允许出现这样的文件”执行：例如文件 `assets/x/a.png` 被拒绝，而空目录 `assets/x/` 被忽略；“恰好一个分组”按含有文件的分组计算。

## 4. 题库文件 `bank.json`（格式 2）

```json
{
  "formatVersion": 2,
  "id": "60000000-0000-4000-8000-000000000001",
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
| `id` | **必填**，小写 UUID，在包内唯一；题库的身份，题库制作时生成、此后不变，题库广场按它对应新旧版本（需求 FR-10 v1.5）。拓展示例题库同样须带 UUID |
| `version` | 可选，作者元数据，不参与去重或更新判断 |
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

按“便宜的检查在前”的顺序执行，任一步失败立即停止，**不写入任何业务数据**（题库、题目、拓展、资源引用、题库实例）。

**资源文件可能已经写入文件存储。** 为了不把最多 256 MiB 的资源留在内存里，解析时 `assets/` 中的文件会直接写入 BlobStore，只保留哈希。失败时，这些文件没有任何引用（没有 `content_bank_asset` 行），对用户不可见，也不影响正确性；内容不可变，再次导入相同内容时直接复用。无人引用的文件由孤立文件清理回收（ADR-007），清理须设宽限期（例如 24 小时），避免删除正在导入、尚未登记引用的文件。为防止有人反复上传失败的包制造孤立文件，导入接口按用户限流。

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

### 9.1 题库内容身份

`content_bank.content_sha256` 定义为以下数组经 **EcmaJson 规范序列化**后的 UTF-8 字节的 SHA-256：

```text
["qf-bank-content-v1", bank.json 解析后的对象,
 按路径排序的 [[assets 下相对路径, 资源 sha256]…],
 按题目顺序排列的每题绑定拓展审核摘要]
```

递归规范化对象键序（EcmaJson 的 canonical 模式），保留数组顺序，数值和字符串沿用 EcmaJson 的 ECMAScript 序列化。资源路径不含 `assets/` 前缀，按 UTF-16 字典序排序；哈希和审核摘要为小写十六进制字符串。调用方按题序解析逐题/默认绑定，传入每题实际使用的审核摘要，重复绑定也不能去重。不改写作者 JSON，不补默认字段，不移除作者元数据；对象键顺序和 JSON 排版不影响身份，作者值、资源路径/内容、题序或实际绑定摘要改变则影响身份。包外目录名、上传者和实例名称不参与。

### 9.2 存储映射

不改写任何文件（原则 1）：

| 包内内容 | 存到哪里 |
| --- | --- |
| 每本题库的 `bank.json` | `content_bank` 一行 + `content_question` 每题一行；题目绑定换成拓展审核摘要 |
| 每本题库的 `assets/` | 文件进 BlobStore；`content_bank_asset` 记录“包内路径 → 内容哈希” |
| 每个拓展的全部文件 | 文件进 BlobStore；`content_extension_file` 记录“路径 → 内容哈希”，`content_extension_package` 一行 |
| 整个 `.qbank` 原始文件 | 题库广场的版本保留原文件，供下载时逐字节返回（需求 FR-11） |

### 9.3 M2 导入执行

上传文件先以流计数并落临时文件，finally 删除；安全解压与结构解析、拓展摘要、题库内容哈希、capabilities/validateBank/outlineBatch/scoreBatch 全在业务事务外完成。按每本题库、拓展分组；只有缺少显式大纲、声明并实现 outline-items 的题目才生成大纲。非空派生项存为 `{level:"parts",items:[…]}`，与显式大纲形状一致；空派生项保持 NULL，显式大纲保留作者值。批量规则请求按**完整序列化 HTTP JSON 请求的 UTF-8 字节数**控制在 8 MiB 内；单题连同封装也超限时返回 413 LIBRARY_ARCHIVE_LIMIT。分批的每个请求使用独立 VM，不跨题库共享上下文。

所有校验通过后，一个 TransactionTemplate 事务登记拓展与文件、题库、题目和资源引用，并为每本题库新建实例。共享键按摘要排序获取，题库唯一键冲突后用 FOR SHARE 当前读复用已有 id；此分支不重复写题目/资源。Blob 文件及其登记可以先于业务事务存在，不属于用户可见业务引用；失败后允许留下未引用 Blob。

题目 content_sha256 使用 EcmaJson canonical 序列化的 `["qf-question-content-v1", 作者题目对象, 实际拓展摘要, 最终大纲或 null, 题库听力材料或 null, 按路径排序的资源清单]` 的 UTF-8 SHA-256；资源清单同 9.1。它保守地包含全部题库资源/听力，避免资源或材料改变后复用旧内容版本；不包含实例 ID 或题序。

M2 的来源 JSON 为 `{"type":"upload","filename":"原文件名.qbank"}`，source_pack_id 暂为 NULL；私人题库包及版本在题库广场里程碑接入。未审核不拒绝导入，也不自动建审核记录；可用性以题库所用全部摘要实时存在 approved 白名单为准，不缓存审批结果。V4 将 admin_extension_review.version 放宽为 VARCHAR(120)。

满分必须非负且能精确存入 DECIMAL(12,4)，不进行舍入或截断；否则 422 SCORE_UNAVAILABLE。拓展/资源相对路径还须能完整存入现有 VARCHAR(255) 列，超出时拒绝并回滚，避免 INSERT IGNORE 静默截断。

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

## 附录 A：沿用的旧版规则

固定参照 `quizforge_web@4522bb81c2126a699fcf72ce61d074dbc1264161`，不读取其工作区变更。以下源码路径以 `core/server/host/` 为基准，文档以 `core/docs/reference/` 为基准；每行注明文件与函数名。正文明确改变的规则优先。本次只做结构解析，不执行 AJV、规则、页面挂载或 SDK 文件服务。

### A.1 JSON 与基础字段

| 规则 | 限制 | 出处 |
| --- | --- | --- |
| JSON | 严格 UTF-8，沿用 TextDecoder 消耗一个起始 BOM；拒绝语法错误、重复键、尾随内容、非有限数值；容器深度最多 64，字符串及对象键最多 2,000,000 个 UTF-16 单元，数字字面量最多 100 字符；负零和极端指数仍接受旧版有限数值语义 | `platform/json.js:parseJson`；`platform/files.js:readJson` |
| 整数声明 | 无小数点、无指数的 JSON 整数字面量；1.0/1e0 不作为整数声明。不透明 data 保留精确十进制数值，不改写源文件 | `platform/json.js:integralField/parseJson`；正文原则 1 |
| 非空文本 | 字符串，JavaScript trim 后非空；不自动修改返回值。本文长度均为 UTF-16 单元，不是 UTF-8 字节数 | `platform/errors.js:text` |
| ID | `^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$`，最多 120，不要求 UUID/semver | `platform/errors.js:id/routeId` |
| 可选说明 | 出现时必须为字符串，允许空串，最多 4000 | `library/collections.js:optional`；`extensions/extension-packages.js:readExtension` |
| 分组名 | 非空，最多 120；不以 . 开头或以空格/点结尾；禁止路径、控制、Windows 禁用字符/设备名；不能为 bank.json、manifest.json、development.json（忽略大小写） | `library/library-management.js:folderName`；`library/library-package.js:incomingGroup` |

### A.2 题库与绑定

| 对象/字段 | 白名单、必填与限制 | 出处 |
| --- | --- | --- |
| 题库根 | 仅 formatVersion/id/version/title/description/extension/features/listening/questions；formatVersion/id/questions 必填。旧加载器没有总白名单；**格式 4 已改**，按正文严格原则拒绝其他字段 | `QUESTION_BANK_FORMAT.md:基本结构`；`library/collections.js:prepareCollection`；正文第 1、4 节 |
| formatVersion | **格式 4 已改**：题库及示例都必须显式为整数 2，不继承旧版省略按 1 的分支 | `extensions/extension-api.js:requireBankFormat`；正文第 4、5 节 |
| id/version/title | 旧版作者元数据可省略，id 按 A.1；**格式 4 已改（v0.6）**：id 必填，为标准 8-4-4-4-12 小写十六进制 UUID，普通题库与拓展示例合计在包内唯一，不自动生成或规范化，用于广场新旧版本对应；content_sha256 仍按 9.1 去重。title 可省略，出现时为非空文本最多 300；version 未被旧加载器校验，**旧版未限制**其类型/长度，不自设上限。旧安装器会补 id/覆盖 title；格式 4 不改写，显示名称单独取 folderName | `library/collections.js:prepareCollection`；`library/library-package.js:installPackage/exportPackage`；`QUESTION_BANK_FORMAT.md:基本结构`；正文第 4、9 节 |
| description | 可选字符串，允许空串，最多 4000 | `library/collections.js:optional/prepareCollection` |
| features | 仅 editing/whiteboard/history/exam 四个 boolean；对象或字段省略默认 true，禁止题目级覆盖。解析器保留显式 false，在线版对 editing/history 的使用策略留给业务层 | `library/features.js:BankFeatures.read`；`library/collections.js:prepareCollection`；正文第 4 节 |
| questions | 数组 1～10000，保持题序 | `library/collections.js:prepareCollection` |
| 每题 | 仅 id/title/extension/outline/listening/data；id/title/data 必填。id 按 A.1，同库区分大小写且唯一；title 非空最多 300；data 必须存在，内部类型/字段不额外加宿主约束。根字段白名单同样是**格式 4 已改** | `library/collections.js:prepareCollection`；正文第 1、4 节 |
| extension | 根默认或逐题绑定，逐题优先；存在的根默认也须有效。每题最终必须有有效绑定；仅 `{path,id?}` | `library/collections.js:prepareCollection`；`library/identity.js:referenceIdentity/findExtension`；正文第 4.1 节 |
| path | 非空字符串，最多 1000，精确指向本包 `extensions/<组>/<拓展>`；不按 ID/名称/大小写猜测 | `library/identity.js:extensionPath/findExtension` |
| 可选 id | 按 A.1 的 ID 规则，与目标 manifest.id 精确一致 | `library/identity.js:referenceIdentity/findExtension` |
| 旧绑定 | **格式 4 已改**：不接受 `{id,version}`、development、typeId 或其他键，没有旧绑定兼容分支 | `library/identity.js:referenceIdentity`；`EXTENSIONS.md:绑定、样例与旧数据`；正文第 2、4.1 节 |

### A.3 显式大纲

| 规则 | 限制 | 出处 |
| --- | --- | --- |
| question | 恰好 `{level:"question",label}`；label 为 1～80 纯文本 | `practice/outline.js:readOutlineConfiguration`；`SUBQUESTION_OUTLINE.md:大纲与稳定 ID` |
| parts | 恰好 `{level:"parts",items}`；items 为 1～100 项，两种形式不可混合 | `practice/outline.js:readOutlineConfiguration` |
| item | 恰好 `{id,label}`；id 1～128、label 1～80；id 在父题内精确唯一，不额外套题目 ID 的 ASCII 正则 | `practice/outline.js:readOutlineItems` |
| 纯文本 | 非空，不含首尾 JavaScript 空白、U+0000..001F/U+007F..009F 或 `<`/`>`，不自动修正 | `practice/outline.js:plain` |
| 能力 | parts 要求有效绑定拓展声明 major=1、minMinor≥1 和 outline-items；question 不要求 | `practice/outline.js:readOutlineConfiguration`；`extensions/extension-api.js:outlineItemsDeclared` |
| 派生目录 | 旧版全库派生目录元数据有 8 MiB 限制；本次不执行 outlineBatch/派生目录，原始 bank.json 仍受正文 8 MiB 限制 | `practice/outline.js:addOutlineBytes/outlineQuestions`；正文第 8 步 9 |

### A.4 听力与图片

| 规则 | 限制 | 出处 |
| --- | --- | --- |
| listening 根 | 仅 materials/examMaterialId，materials 必填；**格式 4 已改**为 1～50（旧版 32） | `resources/listening.js:readListening`；正文第 4.2 节 |
| material | 仅且必须有 id/title/file；id 为 `[A-Za-z0-9_-]{1,80}`，同库唯一；title 非空最多 160 | `resources/listening.js:readListening` |
| file | **格式 4 已改**：本题库直接 assets/<文件名> 且为表中音频；不沿用 media/、WAV/OGG、单文件 8 MiB/全库音频 32 MiB，按正文资源表和全包上限。自由名称不额外套旧 media 的 includes('..') 禁止子串规则 | `resources/listening.js:readListening/audioMime/bindListening`；正文第 2、6、7 节 |
| examMaterialId | 可选，必须为已声明材料 ID | `resources/listening.js:readListening` |
| 每题 listening | 恰好 `{materialId}`，指向本题库声明的材料 | `resources/listening.js:readQuestionListening` |
| 图片引用 | 在题目 data 中递归查 type=image 且 attrs 拥有 assetId 的节点；assetId 为 64 位小写十六进制，必须在本题库的图片哈希索引中；音频哈希、别库图片不能满足 | `resources/resources.js:imageReferences`；正文第 6.3 节 |
| 图片识别 | **格式 4 已改**：自由名称，按正文签名与扩展名配对，不继承旧 SHA 文件名和 detectImageMime 的额外 IHDR/尾标记判断；只识别文件头，不完整解码媒体或推断编解码器 | `resources/resources.js:detectImageMime`；正文第 2、6 节 |

### A.5 manifest、文件和编辑器

| 规则 | 白名单、必填与限制 | 出处 |
| --- | --- | --- |
| manifest 根 | 仅 id/version/name/description/entry/script/style/rules/questionSchema/answerSchema/examples/requiresApi/requiresRichText/dependencies/assets。旧源码显式禁 types/packageFormatVersion，但无总白名单；**格式 4 已改**：按正文严格原则对实际消费字段列白名单 | `extensions/extension-packages.js:readExtension`；正文第 1、5 节 |
| id/version/name | 普通加载要求 id/version 按 A.1（各最多 120），name 非空最多 300；description 可选最多 4000。旧安装器会补 id/version、用文件夹覆盖 name；**格式 4 已改**：不执行文件改写，直接校验完整普通拓展，manifest 保留作者名称 | `extensions/extension-packages.js:readExtension`；`library/library-package.js:installPackage`；正文第 1、5、9 节 |
| 七个文件字段 | entry/script/style/rules/questionSchema/answerSchema/examples 在包中均必填，非空文本最多 240，叶目录内文件必须存在；entry 在普通加载必填，其余六项在旧导入 declaredPaths 也无条件读取 | `extensions/extension-packages.js:FIELDS/readExtension`；`library/library-archive.js:declaredPaths` |
| 文件引用 | 禁绝对路径、反斜杠、冒号、NUL、逃出叶目录；保留 safeFile 对叶内 ./ 和可消去的 .. 的解析语义，不改写 manifest 原值；entry/script/style 必须有效 UTF-8 | `platform/files.js:safeFile/within`；`extensions/extension-packages.js:assetText/pageAssets` |
| examples | 必须为 examples/ 下安全 ASCII JSON 路径（`examples/[A-Za-z0-9._/-]+\.json`，无 .. 段），符合现有 C-04 摘要入口。**格式 4 已改**：内容格式 2，每个根/逐题绑定都须指向所属拓展自己。旧文档建议 id=examples，源码未限制该作者元数据，不增加此限制 | `EXTENSIONS.md:绑定、样例与旧数据`；`library/collections.js:prepareCollection`；C-04/`ExtensionDigest.inspect`；正文第 5 节 |
| 私有文件 | 任一段以 . 开头或为 node_modules/target 时，必须是七字段、assets 或 editor 三字段实际声明的文件；普通非私有额外文件允许，旧版未限制其后缀 | `library/library-archive.js:privatePath/declaredPaths/validatePrivatePaths` |
| 宿主文件 | **格式 4 已改**：.instance.json/.bundle.json/development.json 一律拒绝，声明也不豁免 | 正文第 3.2 节 |
| assets | 可选字符串数组，原字符串去重后最多 100 路径；非空且路径最多 240、存在且叶内。后缀 png/jpg/jpeg/webp/gif/svg/woff/woff2/ttf/otf，忽略大小写；不按题库 ResourceType 解码。去重后内联总字节最多 8 MiB。旧版未限制重复项数量或原数组的独立长度 | `extensions/extension-packages.js:staticAssets/pageAssets/MEDIA` |
| editor.json | 可选；仅 entry/script/style，三项非空路径最多 240、叶内文件存在且 UTF-8。完整白名单是**格式 4 已改**，不继承旧实现忽略其他键 | `library/library-archive.js:declaredPaths`；`extensions/extension-packages.js:editorPage/pageAssets`；正文第 1 节 |
| 文件大小 | **格式 4 已改**：普通拓展文件 8 MiB，manifest/editor 256 KiB，不沿用旧页面单文件 1 MiB；保留内联静态总量规则。C-04 单拓展 2000 文件/64 MiB 仍是现有摘要入口要求，超过返回 LIBRARY_ARCHIVE_LIMIT | `extensions/extension-packages.js:readExtension/assetText`；C-04/`ExtensionDigest.inspect`；正文第 7 节 |

### A.6 API 与富文本

| 对象/规则 | 限制 | 出处 |
| --- | --- | --- |
| requiresApi | 可省略按 1.0；存在时仅 major/minMinor/capabilities，前两项必填 JSON 整数，范围 1..2147483647、0..2147483647；实际支持 major=1、minMinor=0..5 | `extensions/extension-api.js:requireManifestApi/apiVersion`；`COMPATIBILITY.md:版本声明` |
| API capabilities | 可省略/空，数组最多 100；非空、精确不重复的支持能力字符串；**旧版未限制单个 API 能力字符串长度** | `extensions/extension-api.js:requireManifestApi/CAPABILITIES` |
| 基础能力 | practice/editor/editor-drafts/score/manual-review/ai-grading/resources/richtext/navigation/lifecycle，无另加 minor 门槛 | `extensions/extension-api.js:CAPABILITIES/requireManifestApi` |
| 带门槛能力 | outline-items≥1、answer-state≥3、appearance≥4、answer-areas≥5；声明本身不授予业务写权限 | `extensions/extension-api.js:requireManifestApi`；`API.md:Version compatibility` |
| requiresRichText | 与 dependencies 互斥；仅 major/minMinor/documentFormat/documentProfile/capabilities；前四项必填；前三项为 JSON 整数，支持 1、0..1、1；profile 为 basic-v1/advanced-v1 | `resources/richtext-service.js:RichTextService.resolve`；`core/shared/richtext/service.json` |
| 富文本能力 | 可省略；数组最多 100，非空且唯一，每字符串最多 100。basic-v1 仅 basic-formatting/images；advanced-v1 支持 basic-formatting/images/advanced-formatting/tables/math/image-resize/camera-capture | `resources/richtext-service.js:capabilities/resolve`；`core/shared/richtext/service.json:profiles` |
| dependencies | 可选数组最多 4；每项恰好 id/version，只支持 quizforge.richtext；version 为三段 1～4 位十进制数字，按依赖 id 去重 | `resources/richtext-service.js:resolve/validateSdk/version` |
| 边界 | 本次校验声明及固定旧版协议清单，不访问本机 SDK 安装目录/注册表，不执行钩子/AJV。实际 SDK/provider 可用性、派生目录及 Schema/规则语义留给后续集成 | `extensions/extension-packages.js:readExtension` 中 sdk 调用；`RichTextService.resolve`；正文第 8 步 9 与 M2.6a 范围 |

### A.7 覆盖和已知差异

- 包 4、题库 2、资源类型/上限、只按文件判断分组/布局、忽略空目录、允许失败留下无引用 Blob 均依正文；不沿用旧 installPackage 的补写、改名或改绑。出处：正文第 2～9 节；`library/library-package.js:installPackage`。
- 文档 EXTENSIONS/QUESTION_BANK_FORMAT 说显示名取目录；普通 readExtension/prepareCollection 却读取 name/title，旧安装器通过覆盖字段实现。格式 4 不覆盖文件，ParsedBank.folderName 单独给出名称，JSON 和 ParsedExtension.manifest 保留原值。出处：上述函数；正文原则 1、第 4、9 节。
- 根白名单/绑定额外键比旧源码严格，是正文已明确的格式 4 变化；样例 id=examples 只是文档约定，源码无此限制。旧版没有限制的字段不自行定数值。
- 不按数据库列长添加隐含限制。旧 manifest.version 可达 120、name 可达 300；V3 将 content_extension_package 的两列分别放宽到 120/300，不截断已有数据。出处：`readExtension`、数据库设计 4.2 的 content_extension_package。

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-28 | 初稿 |
| v0.2 | 2026-10-28 | 明确：失败时不写入业务数据，资源文件可能已写入文件存储并由孤立文件清理回收；目录规则只按文件判断，空目录忽略（M2.6a 实现中提出） |
| v0.3 | 2026-10-28 | 补充附录 A：固定旧版字段、长度、能力与源码出处，注明格式 4 覆盖及文档/源码差异 |
| v0.4 | 2026-10-29 | 明确题库内容哈希的版本化输入与规范序列化，V3 放宽拓展元数据列长 |
| v0.5 | 2026-10-29 | 补充 M2 导入事务、规则分批、题目内容哈希、满分精度与 V4 审核版本列长 |
| v0.6 | 2026-10-29 | `bank.json` 的 `id` 改为必填 UUID（包内唯一），作为题库在题库广场中的身份 |
