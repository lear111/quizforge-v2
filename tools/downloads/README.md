# 客户端安装包发布

下载页 `/download/`（主要分发 Windows 版）的内容全部来自服务器上的一个目录：安装包文件 + 同目录的 `manifest.json`。
Nginx 直接提供 `/download/manifest.json` 与 `/download/files/<文件名>`，**Java 不读取文件内容**。清单格式与旧平台兼容
（`schemaVersion: 1`，每个平台一项：`platform`、`version`、`label`、`notes`、`file`、`bytes`、`sha256`、`updated`）。

`publish.py` 只用 Python 标准库，在服务器上执行；不连接任何网络服务。

## 目录

- Compose 中 Nginx 把宿主机目录 `${QF_DOWNLOADS_DIR:-deploy/downloads}` 只读挂载为 `/data/downloads`。
- 正式环境建议设 `QF_DOWNLOADS_DIR=/srv/quizforge/downloads`（目录属主为发布者，Nginx 容器只读）。
- 安装包与清单**不进入 Git**（`deploy/downloads/` 已被忽略）。

## 发布新版本

1. 把新安装包上传到服务器的任意临时位置，例如 `~/QuizForge-Setup.exe`；更新说明写进一个文本文件（可多行，最多 2000 字）。
2. 执行：

   ```bash
   python3 -B tools/downloads/publish.py publish --dir /srv/quizforge/downloads \
       --platform windows --version 1.2.0 --label 正式版 \
       --file ~/QuizForge-Setup.exe --notes-file ~/notes-1.2.0.txt
   ```

   工具会：复制为 `QuizForge-1.2.0-windows.exe`（先写临时文件并 fsync，再改名）→ 计算大小与 SHA-256 →
   原子替换 `manifest.json` → 最后删除同平台的旧安装包。同一版本号不能重复发布；失败时目录保持原样。
3. 打开 `https://<站点>/download/` 核对版本号、大小与 SHA-256；必要时在本机用
   `Get-FileHash .\QuizForge-1.2.0-windows.exe -Algorithm SHA256` 核对下载的文件。

其他命令：

```bash
python3 -B tools/downloads/publish.py show   --dir /srv/quizforge/downloads                    # 查看当前清单
python3 -B tools/downloads/publish.py remove --dir /srv/quizforge/downloads --platform android # 下架某个平台
```

同一时间只允许一个发布（目录中的 `.publish.lock`）；工具异常退出留下锁文件时，确认没有其他发布后手工删除。

## 测试

```bash
python3 -B -m unittest discover -s tools/downloads -p 'test_*.py'
```

只使用临时目录中的合成文件；CI 在 backend job 中运行。
