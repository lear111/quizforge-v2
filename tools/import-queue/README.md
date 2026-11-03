# 导入队列的本机测量

这里只使用仓库中的公开样例和新建的合成账号，不读取真实题库、SSH 配置或已有登录态。脚本拒绝非环回 HTTP 地址、远程 Docker 引擎和其他任务的 Compose 项目名。生成文件、账号与测量结果写入被 Git 忽略的 `.artifacts/local/import-queue/`。

## 工作负载

`benchmark.py --mode fixture` 生成固定的 4,209,007 字节 ZIP：12 本题库，每本 96 题，共 1,152 题，混用 8 个不同摘要的拓展。拓展从五种基础题型衍生，题目保留原有 schema，只扩充已有富文本节点；不是用户数据。`variant` 用于生成内容不同、不能命中题目校验缓存的包。

先按项目规则启动独立的 `qf-import-queue` Compose，公开端口使用 8188；不要指向已有项目。测量需要本任务分支的 app，runner 进程上限固定为 4，数据库和 blob 卷属于该独立项目。以下命令从仓库根目录运行：

```powershell
python -B -m unittest discover -s tools/import-queue -p 'test_*.py'
python -B tools/import-queue/benchmark.py --mode fixture
python -B tools/import-queue/benchmark.py --mode baseline --repeats 3
python -B tools/import-queue/benchmark.py --mode queued --repeats 3
python -B tools/import-queue/benchmark.py --mode cache --repeats 3
python -B tools/import-queue/benchmark.py --mode upload
python -B tools/import-queue/benchmark.py --mode foreground
```

脚本会在**自己的合成环境**关闭注册验证码，并为本次新建的上传账号设置管理员身份；不要对已有数据环境运行。浏览器测试需要验证码时，先恢复 `auth_policy.registration_code=TRUE`。

## 指标口径

- `baseline`：旧同步导入接口，逐本处理，测量整个 HTTP 请求耗时；不会写入新校验缓存。
- `queued`：新接收接口的 HTTP 耗时，以及任务 `started_at` 至完成的数据库时间差。每次先清空本项目的校验缓存。将 `QF_IMPORT_EXTENSION_CONCURRENCY` 设为 1/2 并重建 app，可分别测量归组串行与归组并发；其他配置保持一致。
- `cache`：保留上一步服务器写入的校验缓存，再上传同一个包。
- `lruStops`：本次前后 runner Prometheus 指标中 `reason="lru"` 的停止计数差值。
- `upload`：256 KiB、1/4/16 MiB 各上传三次。随机字节不是合法包，会在后台拒绝；接收仍返回 202。分别记录客户端总耗时与响应 `Server-Timing` 的接收/保存时间，接收路径不执行规则。
- `foreground`：不同用户、300 题客观题样例，先预热 20 次提交，再以 2 个并发调用各测 100 次单题提交；一组无导入，另一组与三个不同内容的冷缓存队列任务重叠。P95 使用最近秩法；每次请求必须成功，否则脚本失败。不把读题耗时混入提交耗时。

前台对照每次显式清空本项目的校验缓存，输出逐次耗时和测量窗口内的 LRU 次数，避免重复执行变成缓存命中。导入让行时任务会回到队列，因此应同时检查任务最终完成及规则调用日志；不能把“完全没有运行导入”的空闲对照误称为持续满速导入。

状态轮询只用于本机测量脚本；并发正确性测试使用门控与可控时钟，不依赖固定等待。不同测量模式使用相同的容器配置，并避开本机 Maven/浏览器测试负载；结果不是生产容量结论。结束后对自己的项目执行 `docker compose ... down`。
