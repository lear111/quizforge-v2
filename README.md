# QuizForge V2

QuizForge 在线练习平台的第二版：面向多用户的在线刷题、题库广场与 AI 辅导，目标是至少 100 名活跃用户同时稳定使用。

本仓库为 Monorepo，包含需求、设计文档、源码、测试与部署配置。

## 目录

| 目录 | 内容 |
| --- | --- |
| `docs/requirements/` | 需求规格说明书 |
| `docs/architecture/` | 总体架构 |
| `docs/adr/` | 架构决策记录（ADR） |
| `docs/design/` | 详细设计：数据库、API、rule-runner 协议、题库更新、界面清单 |
| `docs/legacy/` | 旧系统契约清单与数据字典 |
| `backend/` | Java 后端（Spring Boot 模块化单体，ADR-001） |
| `frontend/` | 浏览器前端（复用旧 Web 前端，ADR-006；M4 迁入） |
| `deploy/` | Docker Compose 与 Nginx 配置 |

## 本地运行

需要 Docker（含 Compose）。

```bash
docker compose -f deploy/docker-compose.yml up --build
# 浏览器打开 http://localhost:8088/ ，或：
curl http://localhost:8088/api/health
```

## 后端开发

需要 JDK 21、Maven 3.9+、Docker（集成测试使用 Testcontainers 启动真实 MySQL）。

```bash
cd backend
mvn verify                      # 编译 + 全部测试
docker compose -f ../deploy/docker-compose.yml up -d mysql
mvn spring-boot:run             # 连接本机 3306 的 MySQL（需在 compose 中暴露端口，或设置 QF_DB_URL）
```

数据库结构由 Flyway 管理，脚本位于 `backend/src/main/resources/db/migration/`。**不要手动修改数据库结构**，任何变更都新增一个版本化脚本。

## 开发流程

- `main` 只通过 PR 合并；CI（`.github/workflows/ci.yml`）通过后才能合并。
- 提交信息遵循 Conventional Commits，例如 `feat(practice): ...`、`docs(adr): ...`。
- 重要设计决策先写 ADR，再实现。
