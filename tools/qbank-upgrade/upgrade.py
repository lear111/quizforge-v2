#!/usr/bin/env python3
"""Upgrade documented format-3 qbank archives without executing extension code."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import uuid
import zipfile
import zlib

MIB = 1024 * 1024
MAX_UPLOAD, MAX_TOTAL, MAX_FILE, MAX_JSON, MAX_SMALL = 128*MIB, 256*MIB, 32*MIB, 8*MIB, 256*1024
MARKER = "quizforge-package.json"
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}\Z")
HOST_FILES = {".instance.json", ".bundle.json", "development.json"}


class UpgradeError(ValueError):
    """A file/JSON location and a safe, actionable conversion error."""


def fail(at, message):
    raise UpgradeError(f"{at}：{message}")


@dataclass(frozen=True)
class Number:
    # Preserve numeric tokens, including -0, 1.0, 1e21 and large integers in opaque data.
    literal: str


def encode(value):
    if isinstance(value, Number):
        return value.literal
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return "[" + ",".join(map(encode, value)) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(encode(k) + ":" + encode(v) for k, v in value.items()) + "}"
    raise TypeError(type(value).__name__)


def json_bytes(value):
    return (encode(value) + "\n").encode("utf-8")


def load_json(raw, at, limit=MAX_JSON):
    if len(raw) > limit:
        fail(at, f"JSON 超过 {limit} 字节")

    def pairs(rows):
        result = {}
        for key, value in rows:
            if key in result:
                fail(at, f"JSON 存在重复键 {key!r}")
            result[key] = value
        return result

    def number(token):
        if len(token) > 100 or not math.isfinite(float(token)):
            fail(at, "JSON 数字过长或不是有限数值")
        return Number(token)

    try:
        result = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=pairs,
                            parse_int=number, parse_float=number,
                            parse_constant=lambda _: fail(at, "JSON 不允许 NaN 或 Infinity"))
        pending = [(result, 0)]
        while pending:
            value, depth = pending.pop()
            if isinstance(value, (dict, list)):
                if depth >= 64:
                    fail(at, "JSON 容器深度超过 64")
                if isinstance(value, dict):
                    pending.extend((k, depth+1) for k in value)
                    children = value.values()
                else:
                    children = value
                pending.extend((v, depth+1) for v in children)
            elif isinstance(value, str):
                if len(value.encode("utf-16-le")) // 2 > 2_000_000:
                    fail(at, "JSON 字符串过长")
        return result
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        location = f"，第 {error.lineno} 行第 {error.colno} 列" if isinstance(error, json.JSONDecodeError) else ""
        fail(at, "不是有效的 UTF-8 JSON" + location)


def obj(value, at, allowed=None):
    if not isinstance(value, dict):
        fail(at, "必须为对象")
    if allowed is not None and (unknown := value.keys() - set(allowed)):
        fail(at + "." + sorted(unknown)[0], "没有已定义的转换规则，不能丢弃或猜测此字段")
    return value


def array(value, at, maximum):
    if not isinstance(value, list) or not 1 <= len(value) <= maximum:
        fail(at, f"必须为 1～{maximum} 项的数组")
    return value


def text(value, at, maximum, plain=False):
    if not isinstance(value, str) or not value.strip() or len(value.encode("utf-16-le"))//2 > maximum:
        fail(at, f"必须为非空字符串，最多 {maximum} 个 UTF-16 单元")
    if plain and (value != value.strip() or re.search(r"[\x00-\x1f\x7f-\x9f<>]", value)):
        fail(at, "必须为不含首尾空白、控制字符、尖括号的纯文本")
    return value


def version(value, expected, at):
    if not isinstance(value, Number) or value.literal != str(expected):
        fail(at, f"只支持显式整数 {expected}，不能推断该版本的转换方式")


def safe_path(name):
    at = name or "ZIP 路径"
    if not name or name.startswith("/") or "\\" in name:
        fail(at, "非法或绝对路径")
    parts = (name[:-1] if name.endswith("/") else name).split("/")
    if len(parts) > 32 or parts[0] not in {MARKER, "question-banks", "extensions"}:
        fail(at, "路径超深或不在包顶层白名单中")
    if parts[0] == MARKER and (len(parts) != 1 or name.endswith("/")):
        fail(at, "格式标识必须是根目录文件")
    for part in parts:
        if not part or part in {".", ".."} or part.endswith((" ", ".")) or re.search(r'[<>:"|?*\x00-\x1f\x7f-\x9f]', part):
            fail(at, "路径含空段、禁用字符、点段或结尾空格/点")
        if re.fullmatch(r"CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]", part.split(".")[0], re.I):
            fail(at, "路径含 Windows 保留设备名")
        if part.lower() in HOST_FILES:
            fail(at, "包不能包含宿主身份或开发配置文件")
    return parts


class Archive:
    def __init__(self, source):
        self.source = Path(source)
        if self.source.stat().st_size > MAX_UPLOAD:
            fail("输入包", "超过 128 MiB")
        digest = hashlib.sha256()
        with self.source.open("rb") as stream:
            for chunk in iter(lambda: stream.read(MIB), b""):
                digest.update(chunk)
        self.sha256 = digest.hexdigest()
        self.zip = zipfile.ZipFile(self.source)
        self.entries = {}
        self.directories = 0
        try:
            rows = self.zip.infolist()
            if not rows or len(rows) > 10000:
                fail("ZIP", "条目数必须为 1～10000")
            seen, total = set(), 0
            for info in rows:
                # zipfile truncates filenames at NUL; never use its normalized spelling silently.
                if info.orig_filename != info.filename:
                    fail("ZIP", "条目名含 NUL")
                if not info.flag_bits & 0x800 and not info.filename.isascii():
                    fail("ZIP", "非 ASCII 文件名未声明 UTF-8；不猜测旧文件名编码")
                safe_path(info.filename)
                key = info.filename.rstrip("/").lower()
                if key in seen:
                    fail(info.filename, "路径重复（忽略大小写）")
                seen.add(key)
                mode = info.external_attr >> 16
                if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in {0, stat.S_IFREG, stat.S_IFDIR}):
                    fail(info.filename, "不接受符号链接或特殊文件")
                if info.flag_bits & 1:
                    fail(info.filename, "不接受加密条目")
                if info.is_dir():
                    self.directories += 1
                    continue
                total += info.file_size
                if info.file_size > MAX_FILE or total > MAX_TOTAL:
                    fail(info.filename, "超过单文件 32 MiB 或全包 256 MiB 上限")
                self.entries[info.filename] = info
        except BaseException:
            self.zip.close()
            raise

    def read(self, path, limit=MAX_FILE):
        if path not in self.entries:
            fail(path, "缺少文件")
        with self.zip.open(self.entries[path]) as stream:
            raw = stream.read(limit+1)
            if len(raw) > limit:
                fail(path, f"超过 {limit} 字节")
            return raw

    def document(self, path, limit=MAX_JSON):
        return load_json(self.read(path, limit), path, limit)


def relative_file(value, at):
    value = text(value, at, 240)
    if value.startswith("/") or any(c in value for c in ("\\", ":", "\x00")):
        fail(at, "必须是拓展内相对文件路径")
    parts = []
    for segment in value.split("/"):
        if segment == "..":
            if not parts:
                fail(at, "文件路径逃出拓展")
            parts.pop()
        elif segment not in {"", "."}:
            parts.append(segment)
    return "/".join(parts)


def asset_type(path, raw):
    suffix = PurePosixPath(path).suffix.lower()
    signatures = {
        ".png": raw.startswith(b"\x89PNG\r\n\x1a\n"),
        ".jpg": raw.startswith(b"\xff\xd8\xff"), ".jpeg": raw.startswith(b"\xff\xd8\xff"),
        ".gif": raw.startswith((b"GIF87a", b"GIF89a")),
        ".webp": raw[:4] == b"RIFF" and raw[8:12] == b"WEBP",
        ".mp3": raw.startswith(b"ID3") or (len(raw) >= 2 and raw[0] == 255 and raw[1] & 224 == 224),
        ".m4a": raw[4:8] == b"ftyp",
    }
    if not signatures.get(suffix):
        fail(path, "资源类型未启用，或扩展名与文件头不一致；不自动转码")
    kind = "audio" if suffix in {".mp3", ".m4a"} else "image"
    if len(raw) > (32 if kind == "audio" else 4)*MIB:
        fail(path, "超过该资源类型的大小上限")
    return kind


class Converter:
    def __init__(self, archive):
        self.archive = archive
        self.replacements = {}
        self.renames = {}
        self.manifests = {}
        self.report = {"toolVersion": 1, "inputSha256": archive.sha256,
                       "uuidRule": "UUIDv5(NAMESPACE_URL, qf-qbank-upgrade-v1:<inputSha256>:<original ZIP path>)",
                       "banks": [], "examples": [], "movedAudio": [], "rewrittenAudioReferences": [],
                       "ignoredDirectoryEntries": archive.directories,
                       "warnings": ["示例 JSON 升级会改变拓展审核摘要，导入后需重新审核；其余拓展文件逐字保留。",
                                    "工具不执行拓展 Schema/规则；V2 真实导入是最终校验。"]}

    def prepare(self):
        archive = self.archive
        marker = obj(archive.document(MARKER, MAX_SMALL), MARKER, {"format", "formatVersion", "group", "scope"})
        version(marker.get("formatVersion"), 3, MARKER+".formatVersion")
        if marker.get("format") != "quizforge-bank-group":
            fail(MARKER+".format", "不是题库分组包")
        group = text(marker.get("group"), MARKER+".group", 120)
        scope = marker.get("scope", "group")
        if not isinstance(scope, str) or scope not in {"group", "bank"}:
            fail(MARKER+".scope", "只支持 group / bank")
        banks, extensions = {}, {}
        for path in archive.entries:
            if path == MARKER:
                continue
            parts = path.split("/")
            if len(parts) < 4 or parts[1] != group:
                fail(path, "必须位于清单声明的唯一分组及实体目录内")
            root = "/".join(parts[:3])
            (banks if parts[0] == "question-banks" else extensions).setdefault(root, []).append(path)
            if parts[0] == "question-banks" and (parts[3:] != ["bank.json"] and not (len(parts) == 5 and parts[3] in {"assets", "media"})):
                fail(path, "题库内只接受 bank.json、平铺 assets/ 或 media/ 文件")
        if not 1 <= len(banks) <= 1000 or not 1 <= len(extensions) <= 200:
            fail("ZIP", "题库须为 1～1000 本，拓展须为 1～200 个")
        if scope == "bank" and len(banks) != 1:
            fail(MARKER+".scope", "单题库包必须恰好一本题库")
        for root in extensions:
            manifest = obj(archive.document(root+"/manifest.json", MAX_SMALL), root+"/manifest.json",
                           {"id", "version", "name", "description", "requiresApi", "requiresRichText", "dependencies",
                            "entry", "script", "style", "rules", "questionSchema", "answerSchema", "examples", "assets"})
            self.manifests[root] = manifest
            for field in ("id", "version"):
                value = text(manifest.get(field), root+"/manifest.json."+field, 120)
                if not ID.fullmatch(value):
                    fail(root+"/manifest.json."+field, "无效的拓展 ID/版本；不自动补写元数据")
            text(manifest.get("name"), root+"/manifest.json.name", 300)
            for field in ("entry", "script", "style", "rules", "questionSchema", "answerSchema", "examples"):
                target = root+"/"+relative_file(manifest.get(field), root+"/manifest.json."+field)
                if target not in archive.entries:
                    fail(target, "manifest 声明的文件不存在")
            example = manifest["examples"]
            if not re.fullmatch(r"examples/[A-Za-z0-9._/-]+\.json", example) or ".." in example.split("/"):
                fail(root+"/manifest.json.examples", "不支持的示例文件路径")
            for path in extensions[root]:
                if archive.entries[path].file_size > (MAX_SMALL if path.endswith(("/manifest.json", "/editor.json")) else MAX_JSON):
                    fail(path, "超过拓展文件的格式 4 大小上限")
        used = set()
        for root in banks:
            used.update(self.convert_bank(root+"/bank.json", root, None))
        if scope == "bank" and used != self.manifests.keys():
            fail(MARKER+".scope", "单题库包含未被题库绑定的拓展，不能静默丢弃")
        for root, manifest in self.manifests.items():
            path = root+"/"+manifest["examples"]
            self.convert_bank(path, path.rsplit("/", 1)[0], root)
        marker["formatVersion"] = 4
        self.replacements[MARKER] = json_bytes(marker)
        self.report.update(group=group, scope=scope, packageVersion={"from": 3, "to": 4})

    def binding(self, value, at, example_owner):
        ref = obj(value, at, {"path", "id"})
        path = ref.get("path")
        if not isinstance(path, str) or path not in self.manifests:
            fail(at+".path", "必须准确绑定包内拓展，不能由 ID/版本/名称猜测")
        if "id" in ref and ref["id"] != self.manifests[path].get("id"):
            fail(at+".id", "与目标 manifest.id 不一致")
        if example_owner is not None and example_owner != path:
            fail(at+".path", "拓展示例必须绑定所属拓展自己")
        return path

    def convert_bank(self, path, root, example_owner):
        bank = obj(self.archive.document(path), path,
                   {"formatVersion", "id", "version", "title", "description", "extension", "features", "listening", "questions"})
        # The documented format-3 loader treats an omitted bank version as 1.
        if "formatVersion" in bank:
            version(bank["formatVersion"], 1, path+".formatVersion")
        if "title" in bank:
            text(bank["title"], path+".title", 300)
        if "description" in bank and (not isinstance(bank["description"], str) or len(bank["description"].encode("utf-16-le"))//2 > 4000):
            fail(path+".description", "须为最多 4000 个 UTF-16 单元的字符串")
        if "features" in bank:
            features = obj(bank["features"], path+".features", {"editing", "whiteboard", "history", "exam"})
            if any(not isinstance(value, bool) for value in features.values()):
                fail(path+".features", "开关必须为布尔值")
        assets = self.assets(root)
        materials = self.listening(bank.get("listening"), path+".listening", assets, root) if "listening" in bank else set()
        fallback = self.binding(bank["extension"], path+".extension", example_owner) if "extension" in bank else None
        used, ids, groups, previous = set(), set(), [], None
        if fallback:
            used.add(fallback)
        questions = array(bank.get("questions"), path+".questions", 10000)
        for index, raw in enumerate(questions):
            at = f"{path}.questions[{index}]"
            question = obj(raw, at, {"id", "title", "extension", "listening", "outline", "data"})
            qid = question.get("id")
            if not isinstance(qid, str) or not ID.fullmatch(qid) or qid in ids:
                fail(at+".id", "题目 ID 无效或重复")
            ids.add(qid)
            text(question.get("title"), at+".title", 300)
            if "data" not in question:
                fail(at+".data", "缺少题目数据")
            owner = self.binding(question["extension"], at+".extension", example_owner) if "extension" in question else fallback
            if owner is None:
                fail(at+".extension", "缺少有效拓展绑定")
            used.add(owner)
            if "listening" in question:
                listening = obj(question["listening"], at+".listening", {"materialId"})
                if not isinstance(listening.get("materialId"), str) or listening["materialId"] not in materials:
                    fail(at+".listening.materialId", "引用了未声明的听力材料")
            self.images(question["data"], at+".data", assets)
            item = {"question": qid}
            if "outline" in question:
                outline = obj(question.pop("outline"), at+".outline", {"level", "label", "items"})
                if outline.get("level") == "question":
                    obj(outline, at+".outline", {"level", "label"})
                    item["label"] = text(outline.get("label"), at+".outline.label", 80, True)
                elif outline.get("level") == "parts":
                    obj(outline, at+".outline", {"level", "items"})
                    api = self.manifests[owner].get("requiresApi", {})
                    obj(api, owner+"/manifest.json.requiresApi")
                    minor = api.get("minMinor")
                    capabilities = api.get("capabilities")
                    if api.get("major") != Number("1") or not isinstance(minor, Number) or not minor.literal.isdigit() or int(minor.literal) < 1 or not isinstance(capabilities, list) or "outline-items" not in capabilities:
                        fail(at+".outline", "parts 需要拓展声明 API 1.1+ 的 outline-items")
                    parts = array(outline.get("items"), at+".outline.items", 100)
                    part_ids = set()
                    for p, part in enumerate(parts):
                        pos = at+f".outline.items[{p}]"
                        obj(part, pos, {"id", "label"})
                        pid = text(part.get("id"), pos+".id", 128, True)
                        if pid in part_ids:
                            fail(pos+".id", "小题 ID 重复")
                        part_ids.add(pid)
                        text(part.get("label"), pos+".label", 80, True)
                    item["parts"] = parts
                else:
                    fail(at+".outline.level", "只支持 question / parts")
            if previous != owner:
                name = text(self.manifests[owner].get("name"), owner+"/manifest.json.name", 80, True)
                groups.append({"group": name, "items": []})
                previous = owner
            groups[-1]["items"].append(item)
        new_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"qf-qbank-upgrade-v1:{self.archive.sha256}:{path}"))
        bank.update(formatVersion=2, id=new_id, outline=groups)
        raw = json_bytes(bank)
        if len(raw) > MAX_JSON:
            fail(path, "转换后的题库 JSON 超过 8 MiB")
        self.replacements[path] = raw
        self.report["examples" if example_owner else "banks"].append({
            "path": path, "uuid": new_id, "questions": len(questions), "outlineGroups": len(groups),
            "changes": ["formatVersion→2", "id→确定性 UUIDv5", "逐题 outline→顶层 outline（顺序保留）"]})
        return used

    def assets(self, root):
        result = {}
        for path in self.archive.entries:
            if not path.startswith(root+"/"):
                continue
            relative = path[len(root)+1:]
            pieces = relative.split("/")
            if pieces[0] not in {"assets", "media"}:
                continue
            if len(pieces) != 2:
                fail(path, "资源目录不能含子目录")
            raw = self.archive.read(path)
            kind = asset_type(path, raw)
            if pieces[0] == "media" and kind != "audio":
                fail(path, "media/ 只迁移音频，不能猜测其他文件的用途")
            target = root+"/assets/"+pieces[1]
            if pieces[0] == "media":
                if any(entry.lower() == target.lower() for entry in self.archive.entries):
                    fail(path, "迁移目标与已有 assets 文件重名，不能覆盖")
                self.renames[path] = target
                self.report["movedAudio"].append({"from": path, "to": target})
            key = "assets/"+pieces[1]
            if any(existing.lower() == key.lower() for existing in result):
                fail(path, "迁移后资源路径重名")
            result[key] = (kind, hashlib.sha256(raw).hexdigest())
        return result

    def listening(self, value, at, assets, root):
        config = obj(value, at, {"materials", "examMaterialId"})
        ids = set()
        for index, raw in enumerate(array(config.get("materials"), at+".materials", 50)):
            pos = f"{at}.materials[{index}]"
            row = obj(raw, pos, {"id", "title", "file"})
            mid = row.get("id")
            if not isinstance(mid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", mid) or mid in ids:
                fail(pos+".id", "听力材料 ID 无效或重复")
            ids.add(mid)
            text(row.get("title"), pos+".title", 160)
            file = row.get("file")
            if isinstance(file, str) and file.startswith("media/"):
                if root+"/"+file not in self.renames:
                    fail(pos+".file", "引用的 media 音频不存在")
                self.report["rewrittenAudioReferences"].append({"location": pos+".file", "from": file, "to": "assets/"+file[len("media/"):]})
                row["file"] = file = "assets/"+file[len("media/"):]
            if not isinstance(file, str) or file not in assets or assets[file][0] != "audio":
                fail(pos+".file", "必须引用本题库可识别的音频文件")
        if "examMaterialId" in config and (not isinstance(config["examMaterialId"], str) or config["examMaterialId"] not in ids):
            fail(at+".examMaterialId", "引用未声明的听力材料")
        return ids

    @staticmethod
    def images(data, at, assets):
        hashes = {sha for kind, sha in assets.values() if kind == "image"}
        pending = [(data, at)]
        while pending:
            value, pos = pending.pop()
            if isinstance(value, dict):
                attrs = value.get("attrs")
                if value.get("type") == "image" and isinstance(attrs, dict) and "assetId" in attrs and (not isinstance(attrs["assetId"], str) or attrs["assetId"] not in hashes):
                    fail(pos+".attrs.assetId", "引用了不存在的本题库图片")
                pending.extend((child, pos+"."+key) for key, child in value.items())
            elif isinstance(value, list):
                pending.extend((child, f"{pos}[{i}]") for i, child in enumerate(value))


def upgrade(source, destination):
    """Write an exclusive, atomic output; return a content-free conversion report."""
    destination = Path(destination)
    if destination.exists():
        fail("输出包", "文件已存在；请指定新文件名，不覆盖原包")
    archive = None
    temporary = None
    try:
        archive = Archive(source)
        converter = Converter(archive)
        converter.prepare()
        fd, temporary = tempfile.mkstemp(prefix=".qbank-upgrade-", suffix=".tmp", dir=destination.parent)
        with os.fdopen(fd, "w+b") as stream:
            with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as out:
                total = 0
                for path in sorted(archive.entries):
                    raw = converter.replacements.get(path)
                    if raw is None:
                        raw = archive.read(path)
                    total += len(raw)
                    if total > MAX_TOTAL:
                        fail("输出包", "解压总大小超过 256 MiB")
                    info = zipfile.ZipInfo(converter.renames.get(path, path), (1980, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = (stat.S_IFREG | 0o644) << 16
                    out.writestr(info, raw)
            stream.flush()
            os.fsync(stream.fileno())
            if stream.tell() > MAX_UPLOAD:
                fail("输出包", "压缩大小超过 128 MiB")
        # Unlike replace(), linking cannot clobber a concurrently created destination.
        os.link(temporary, destination)
        digest = hashlib.sha256()
        with destination.open("rb") as completed:
            for chunk in iter(lambda: completed.read(MIB), b""):
                digest.update(chunk)
        converter.report.update(outputSha256=digest.hexdigest(),
                                extensionFilesCopiedUnchanged=sum(1 for p in archive.entries if p.startswith("extensions/") and p not in converter.replacements and p not in converter.renames))
        return converter.report
    except UpgradeError:
        raise
    except (OSError, UnicodeError, zipfile.BadZipFile, zlib.error, NotImplementedError, RuntimeError) as error:
        raise UpgradeError("ZIP/文件：无法完成转换（损坏、CRC/压缩/编码不支持、路径不可用或输出已存在）") from error
    finally:
        if archive is not None:
            archive.zip.close()
        if temporary is not None:
            Path(temporary).unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="格式 3 → 4；不执行拓展代码、不覆盖文件。")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--report", type=Path, help="另写 JSON 转换报告（不含题目内容，不覆盖）")
    args = parser.parse_args(argv)
    if args.report is not None and (args.report.exists() or args.report.resolve() == args.output.resolve()):
        parser.error("报告路径已存在或与输出包相同")
    try:
        report = upgrade(args.input, args.output)
        body = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.report is not None:
            with args.report.open("x", encoding="utf-8") as stream:
                stream.write(body)
        print(body, end="")
        return 0
    except UpgradeError as error:
        print(f"转换失败：{error}", file=sys.stderr)
        return 2
    except OSError:
        print("转换包已生成，但报告无法写入；请检查报告目录权限。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
