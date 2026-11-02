"""发布或移除客户端安装包，并原子更新同目录下的 manifest.json（下载页的数据来源）。

只使用 Python 标准库，在服务器上由作者执行；不连接任何网络服务。清单格式与旧平台兼容
（schemaVersion 1，items 中每个平台一项），另增加可选的多行更新说明 notes。

    python3 -B tools/downloads/publish.py publish --dir /srv/quizforge/downloads \
        --platform windows --version 1.2.0 --file ./QuizForge-Setup.exe --notes-file ./notes.txt
    python3 -B tools/downloads/publish.py remove --dir /srv/quizforge/downloads --platform android
    python3 -B tools/downloads/publish.py show --dir /srv/quizforge/downloads

成功返回 0 并输出 JSON；失败返回 2，目录中原有的安装包与清单保持不变。
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import tempfile

PLATFORMS = {"windows": ".exe", "android": ".apk"}
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
VERSION = re.compile(r"[0-9A-Za-z.+-]{1,40}")
LABEL_MAX = 20
NOTES_MAX = 2000
MANIFEST = "manifest.json"
LOCK = ".publish.lock"


class PublishError(Exception):
    pass


def _fsync_dir(directory):
    if os.name == "nt":
        return
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write_atomic(directory, name, data):
    fd, temp = tempfile.mkstemp(prefix=".manifest-", suffix=".part", dir=directory)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp, os.path.join(directory, name))
        _fsync_dir(directory)
    finally:
        if os.path.exists(temp):
            os.remove(temp)


def read_manifest(directory):
    path = os.path.join(directory, MANIFEST)
    if not os.path.exists(path):
        return []
    if os.path.islink(path) or os.path.getsize(path) > 65536:
        raise PublishError("清单文件不可用（符号链接或超过 64 KiB）")
    with open(path, "r", encoding="utf-8") as source:
        value = json.load(source)
    items = value.get("items") if isinstance(value, dict) else None
    if not isinstance(items, list):
        raise PublishError("清单格式不正确：缺少 items 数组")
    for item in items:
        platform = item.get("platform")
        if platform not in PLATFORMS or not isinstance(item.get("file"), str) or not NAME.fullmatch(item["file"]):
            raise PublishError("清单中有无法识别的条目，请人工检查后再发布")
    return items


def _save(directory, items):
    order = list(PLATFORMS)
    items = sorted(items, key=lambda item: order.index(item["platform"]))
    data = json.dumps({"schemaVersion": 1, "items": items}, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    _write_atomic(directory, MANIFEST, data)


class _Lock:
    def __init__(self, directory):
        self.path = os.path.join(directory, LOCK)

    def __enter__(self):
        try:
            self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise PublishError(f"另一个发布正在进行；如确认没有，请删除 {self.path}")
        return self

    def __exit__(self, *exc):
        os.close(self.fd)
        os.remove(self.path)


def _directory(path):
    if not os.path.isdir(path) or os.path.islink(path):
        raise PublishError("下载目录不存在或是符号链接")
    return os.path.abspath(path)


def publish(directory, platform, version, source, label="", notes="", now=None):
    directory = _directory(directory)
    if platform not in PLATFORMS:
        raise PublishError("平台应为 windows 或 android")
    if not VERSION.fullmatch(version):
        raise PublishError("版本号只能包含字母、数字、点、加号和减号（最多 40 个字符）")
    if len(label) > LABEL_MAX:
        raise PublishError(f"版本标签最多 {LABEL_MAX} 个字符")
    notes = notes.replace("\r\n", "\n").strip()
    if len(notes) > NOTES_MAX:
        raise PublishError(f"更新说明最多 {NOTES_MAX} 个字符")
    if not os.path.isfile(source):
        raise PublishError("安装包文件不存在")
    filename = f"QuizForge-{version}-{platform}{PLATFORMS[platform]}"
    target = os.path.join(directory, filename)
    with _Lock(directory):
        items = read_manifest(directory)
        previous = next((item for item in items if item["platform"] == platform), None)
        if previous and previous["file"] == filename:
            raise PublishError("该平台已经发布了同一版本号；请使用新的版本号")
        if os.path.exists(target):
            raise PublishError(f"目标文件 {filename} 已存在，请先确认它不是正在使用的安装包")
        digest = hashlib.sha256()
        size = 0
        fd, temp = tempfile.mkstemp(prefix=".installer-", suffix=".part", dir=directory)
        try:
            with os.fdopen(fd, "wb") as output, open(source, "rb") as input_file:
                for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
                    digest.update(chunk)
                    output.write(chunk)
                    size += len(chunk)
                output.flush()
                os.fsync(output.fileno())
            if size == 0:
                raise PublishError("安装包是空文件")
            os.replace(temp, target)
            item = {
                "platform": platform,
                "version": version,
                "label": label,
                "notes": notes,
                "file": filename,
                "bytes": size,
                "sha256": digest.hexdigest(),
                "updated": (now or datetime.datetime.now(datetime.timezone.utc)).isoformat().replace("+00:00", "Z"),
            }
            try:
                _save(directory, [entry for entry in items if entry["platform"] != platform] + [item])
            except Exception:
                os.remove(target)
                raise
        finally:
            if os.path.exists(temp):
                os.remove(temp)
        # 清单已指向新文件后，再删除同平台的旧安装包。
        if previous:
            old = os.path.join(directory, previous["file"])
            if os.path.isfile(old) and not os.path.islink(old):
                os.remove(old)
        return item


def remove(directory, platform):
    directory = _directory(directory)
    if platform not in PLATFORMS:
        raise PublishError("平台应为 windows 或 android")
    with _Lock(directory):
        items = read_manifest(directory)
        previous = next((item for item in items if item["platform"] == platform), None)
        if previous is None:
            return {"removed": False}
        _save(directory, [item for item in items if item["platform"] != platform])
        old = os.path.join(directory, previous["file"])
        if os.path.isfile(old) and not os.path.islink(old):
            os.remove(old)
        return {"removed": True, "file": previous["file"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description="发布或移除 QuizForge 客户端安装包")
    commands = parser.add_subparsers(dest="command", required=True)
    publish_cmd = commands.add_parser("publish")
    publish_cmd.add_argument("--dir", required=True)
    publish_cmd.add_argument("--platform", required=True, choices=list(PLATFORMS))
    publish_cmd.add_argument("--version", required=True)
    publish_cmd.add_argument("--file", required=True)
    publish_cmd.add_argument("--label", default="")
    notes = publish_cmd.add_mutually_exclusive_group()
    notes.add_argument("--notes", default="")
    notes.add_argument("--notes-file")
    remove_cmd = commands.add_parser("remove")
    remove_cmd.add_argument("--dir", required=True)
    remove_cmd.add_argument("--platform", required=True, choices=list(PLATFORMS))
    show_cmd = commands.add_parser("show")
    show_cmd.add_argument("--dir", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "publish":
            text = args.notes
            if args.notes_file:
                with open(args.notes_file, "r", encoding="utf-8") as source:
                    text = source.read()
            result = publish(args.dir, args.platform, args.version, args.file, args.label, text)
        elif args.command == "remove":
            result = remove(args.dir, args.platform)
        else:
            result = {"items": read_manifest(_directory(args.dir))}
    except (PublishError, OSError, ValueError) as error:
        print(f"发布失败：{error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
