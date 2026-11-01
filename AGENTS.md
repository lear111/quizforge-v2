# QuizForge V2：给 AI 编码助手的项目规则

本文件同时作为 `CLAUDE.md`（Claude Code）与 `AGENTS.md`（Codex）的内容，所有参与开发的 AI 助手都必须遵守。

## 项目与当前阶段

- QuizForge V2：多用户在线题库与练习平台，替换旧在线平台（`lear111/quizforge_hub`）。后端重新实现，浏览器前端复用旧版并改造（ADR-006）。
- **当前阶段与优先级以 [docs/plan/01-launch-roadmap.md](docs/plan/01-launch-roadmap.md) 为准**；练习空间的产品规则以 [docs/plan/02-practice-acceptance.md](docs/plan/02-practice-acceptance.md) 为准。需求见 `docs/requirements/01-requirements.md`，设计见 `docs/design/`，架构决定见 `docs/adr/`。
- 产品决定由作者（王国松）做出。文档与需求有歧义、或与现有代码冲突时，**在报告中列出，不要自行决定**。

## 技术栈

Spring Boot 4.1 / JDK 21 模块化单体；MySQL 8.4（InnoDB，REPEATABLE READ）；Flyway；MyBatis 注解 SQL；Jackson 3（`tools.jackson.*`）；rule-runner（Node 22，执行拓展规则的沙箱）；Docker Compose（nginx、app、mysql、rule-runner）；测试用 Testcontainers。

## 协作流程（必须遵守）

1. **所有改动都通过 PR 进入 `main`**，不得直接推送 `main`。每个任务一个新分支，从最新 `main` 创建。
2. PR 描述写清：改了什么、为什么、文件清单、测试对应的需求、验证结果、发现的问题。CI 四个 job（Backend、Frontend、Rule runner、Compose smoke）全部通过后，**不要自行合并**，由审查者合并。
3. 任务说明中标明"作者编写"的文件，不得修改其逻辑；发现缺陷只在报告中写出复现步骤和失败的测试。
4. 提交信息遵循 Conventional Commits（`feat(practice): …`、`docs(plan): …`）。
5. 文档、报告、代码注释中的说明使用**中文**。

## 数据库与并发

- **不得修改已应用的 Flyway 迁移**，任何结构变更都新增版本化脚本。
- MyBatis `#{}` 中的名字必须与 `@Param` 一致；SQL 中写数据库列名（下划线），不是 Java 名。
- 用户身份（`userId`）只能从登录会话获取，不信任请求体、路径或模型输出中的用户标识；归属检查写在 SQL 条件里（`AND user_id = ?` 或 `INSERT … SELECT … WHERE user_id = ?`），防止 IDOR。
- 写操作沿用：乐观锁（`revision`）、幂等回执（`requestId` + `practice_request_receipt`，冲突后在新事务中重查）、慢操作（rule-runner、AI）放在事务外。
- 避免对不存在的行加锁读（间隙锁死锁）；多行加锁顺序固定为 `practice_context` → `practice_question_state` → `practice_round`。
- 带归属条件的写入要检查影响行数，0 行时按语义抛出 409 或 `IllegalStateException`。
- 分数用 `BigDecimal`，存储前 HALF_UP 四位小数；未评分为 `null`，汇总时按 0 计。

## 性能约束

- 高频路径（读题、保存草稿、白板保存）**不得做整本题库级别的处理**；能延迟计算的不要提前计算。
- 大文件（题库资源、答题图片、音视频）由 nginx 直接传输，Java 只做权限检查。
- 每个写操作都有大小上限。

## 安全

- 不信任上传文件的扩展名与 Content-Type，按文件头判断类型；解压遵循 `QbankArchiveReader` 的规则。
- 资源按 SHA-256 访问时必须检查读取权限。
- 未提交的题目不得向前端或 AI 暴露标准答案。

## 本地运行与测试的约束

- 本地 Compose 一律使用**独立的项目名**（`docker compose -p qf-<任务名> …`）与空闲端口，不得停止或删除他人的项目和数据卷；结束时 `down`（不加 `-v`，除非是自己的临时项目）。
- **只在本机执行**：不得连接任何远程服务器（包括作者的正式服、测试服），不得读取或使用本机的 SSH 配置。
- 真实题库、用户数据、截图、登录态放在 git 忽略的 `local/` 目录，不得提交到仓库或上传到 CI。
- 本机可能同时有多个 AI 助手在运行测试：完整的 `mvn verify` 与浏览器测试耗费资源，必要时只运行相关测试，完整验证交给 CI；测试中不使用固定 `sleep` 判断并发状态。

## 常用命令

```bash
mvn -B -ntp -f backend/pom.xml verify                 # 后端编译与全部测试（需要 Docker）
cd frontend && npm run build && npm test               # 前端
cd rule-runner && npm test                             # rule-runner
docker compose -f deploy/docker-compose.yml up --build # 本地运行，http://localhost:8088/
```
