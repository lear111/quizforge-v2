# 已审核资源创建实例的本机探针

先使用独立项目 `qf-my-library` 启动本地 Compose，端口 8288、公开来源 `http://localhost:8288`。
运行：

```powershell
python tools/my-library/probe.py --base-url http://localhost:8288 --project qf-my-library --count 30
```

探针只允许本机 HTTP 地址，禁止代理和重定向；只操作本任务的 Compose。它注册合成账号并通过自己的测试库提升为管理员，上传公开样例 `samples/loadtest-300.qbank`。等待后台校验完成后，预热 5 次、测量 30 次独立创建实例的完整 HTTP 耗时，输出原始样本、中位数与 P95。不会输出账号或会话。

注册期间短暂关闭本测试库的注册验证码，`finally` 恢复原值；不要与浏览器注册验收同时运行。样例与实例保留在本测试项目中，结束后执行 `docker compose -p qf-my-library ... down`。这不是容量压测，也不对共享本机的其他进程做任何改动。
