# 备份与恢复演练记录

## 2026-11-07 本机演练（L8）

- 执行者：本地 Claude；只在本机执行，独立 Compose 项目 `qf-l8`，结束后 `down -v` 清理。
- 配置：`docker-compose.yml` + `docker-compose.prod.yml` + `docker-compose.prod-drill.yml`（生产配置，唯一的差别是验证码为 fake、
  判分为 server、放宽频率限制，便于脚本写入数据）。HTTPS 使用本机自签证书，`https://localhost:18443`。
- 机器：作者的 Windows 开发机（Docker Desktop），与其他项目同时运行。耗时只说明脚本可用，不代表服务器上的数值。
- 脚本：`deploy/backup/backup.sh`、`verify.sh`、`restore.sh`，数据写入与检查用 `deploy/backup/drill.mjs`。

### 步骤与结果

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 1. 写入数据 | `drill.mjs seed`：注册账号、管理员发布 `samples/basic-types.qbank` 到广场、学习者导入、提交作答、结束并重新开始一轮（产生冻结历史）、再提交 | 账号 10 个，题库包、题库实例、历史各 1 |
| 2. 备份 | `backup.sh` | 28 秒；546 KiB（`mysql.sql.gz` 30 KiB、`blobs.tar` 506 KiB）；账号 10、文件 52、Flyway 31；自动校验通过 |
| 3. 完整校验 | `verify.sh <目录> --full` | 52 个文件的内容与文件名一致 |
| 4. 篡改检测 | 复制备份后改动一个字节，再 `verify.sh` | 失败：“校验和不符”，返回非 0 |
| 5. 删除数据 | `docker compose -p qf-l8 … down -v` | 数据卷剩余 0 |
| 6. 不加 `--yes` | `restore.sh <目录>` | 只校验并说明将覆盖的项目，未做任何修改 |
| 7. 恢复 | `restore.sh <目录> --yes`（数据卷不存在，从零创建） | 155 秒（其中等待 MySQL 初始化约 60 秒、应用启动约 60 秒）；四个服务 healthy |
| 8. 恢复后检查 | `drill.mjs check` | 9 项全部通过，见下 |
| 9. 保留策略 | `QF_BACKUP_KEEP=2` 连续备份 3 次 | 删除最旧的一份，保留 2 份 |

恢复后检查（经过 Nginx 的 HTTPS）：

1. 账号能用原密码登录
2. 账号数量与备份前一致（10）
3. 题库能打开，题目能读取
4. 当前作答还在（状态 submitted、答案与 revision 5 一致）
5. 冻结历史与备份前完全相同（逐字段比较）
6. 广场匿名下载与原始题库文件逐字节一致（124803 字节，SHA-256 `808ae8e05d3a…`）
7. 客户端固定版本下载逐字节一致
8. 客户端固定版本导入清单不变
9. 恢复后可以继续写入（重做一题成功）

### 演练中发现并修正的问题

- `verify.sh` 原来用 `gzip -dc | grep -q`：`grep` 找到后提前退出，`gzip` 收到 SIGPIPE，在 `pipefail` 下把好的备份判为失败。改为 `grep -c`。
- Git Bash 中 `tar -tf C:/…` 把 `C:` 当成远程主机；文件计数改为通过标准输入读归档（Linux 上不受影响，但脚本两边都能用）。
- 检查脚本最初用历史 JSON 的整体哈希比较，恢复后哈希不同、逐字段比较完全相同：响应中对象的键顺序可能不同。改为逐字段比较，失败时列出不同的位置。

### 在服务器上复做

作者在服务器上线前按 `01-deploy.md` 第 6.4 节，在**另一个项目名**（例如 `-p qf-restore-test`，使用不同端口）或另一台机器上恢复一次最近的正式备份，
用一个已知账号检查上面的 1、3、4、5、6 项，把日期、备份大小、恢复耗时补充到这里。
