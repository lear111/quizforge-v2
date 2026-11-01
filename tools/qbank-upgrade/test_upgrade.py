"""Synthetic archives only: no personal banks, Spring, Docker or extra dependencies."""
import copy
import hashlib
import json
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
import uuid
import warnings
import zipfile
from unittest.mock import patch

import upgrade as tool


GROUP = "示例组"
BANK = f"question-banks/{GROUP}/题库"
EXT = f"extensions/{GROUP}/题型甲"
PNG = b"\x89PNG\r\n\x1a\nsynthetic-image"


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()


def question(qid="q1", **kwargs):
    return {"id": qid, "title": "示例题", "data": {"answer": "synthetic"}, **kwargs}


def fixture(scope="group"):
    manifest = {"id": "example", "version": "1.0.0", "name": "题型甲", "entry": "practice.html",
                "script": "practice.js", "style": "practice.css", "rules": "rules.js",
                "questionSchema": "question.schema.json", "answerSchema": "answer.schema.json",
                "examples": "examples/bank.json", "requiresApi": {"major": 1, "minMinor": 1, "capabilities": ["practice", "outline-items"]}}
    bank = {"formatVersion": 1, "id": "old-author-id", "extension": {"path": EXT},
            "questions": [question(outline={"level": "question", "label": "一"})]}
    example = copy.deepcopy(bank)
    example["id"] = "examples"
    return {tool.MARKER: packed({"format": "quizforge-bank-group", "formatVersion": 3, "group": GROUP, "scope": scope}),
            BANK+"/bank.json": packed(bank), EXT+"/manifest.json": packed(manifest),
            EXT+"/examples/bank.json": packed(example), EXT+"/practice.html": b"<!doctype html>",
            EXT+"/practice.js": b"// literal source\r\n", EXT+"/practice.css": b"/* style */\n",
            EXT+"/rules.js": b"QF.defineType({});\r\n", EXT+"/question.schema.json": b'{}',
            EXT+"/answer.schema.json": b'{}', EXT+"/README.md": b"unchanged\x00content"}


def edit(files, path, change):
    value = json.loads(files[path])
    change(value)
    files[path] = packed(value)


class UpgradeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source, self.output = self.root/"old.qbank", self.root/"new.qbank"

    def write(self, files):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.source, "w", zipfile.ZIP_DEFLATED) as archive:
                for path, content in files.items() if isinstance(files, dict) else files:
                    archive.writestr(path, content)

    def convert(self, files=None):
        self.write(fixture() if files is None else files)
        report = tool.upgrade(self.source, self.output)
        with zipfile.ZipFile(self.output) as archive:
            return report, {path: archive.read(path) for path in archive.namelist()}

    def rejects(self, files, position=None):
        self.write(files)
        original = self.source.read_bytes()
        with self.assertRaises(tool.UpgradeError) as error:
            tool.upgrade(self.source, self.output)
        if position:
            self.assertIn(position, str(error.exception))
        self.assertFalse(self.output.exists())
        self.assertEqual(original, self.source.read_bytes())
        self.assertEqual([], list(self.root.glob(".qbank-upgrade-*")))

    def test_group_versions_ids_outline_and_extension_bytes(self):
        files = fixture()
        report, converted = self.convert(files)
        self.assertEqual(4, json.loads(converted[tool.MARKER])["formatVersion"])
        for entry in [BANK+"/bank.json", EXT+"/examples/bank.json"]:
            bank = json.loads(converted[entry])
            self.assertEqual(2, bank["formatVersion"])
            self.assertEqual(5, uuid.UUID(bank["id"]).version)
            self.assertEqual([{"group": "题型甲", "items": [{"question": "q1", "label": "一"}]}], bank["outline"])
            self.assertNotIn("outline", bank["questions"][0])
            self.assertEqual({"answer": "synthetic"}, bank["questions"][0]["data"])
        self.assertNotEqual(json.loads(converted[BANK+"/bank.json"])["id"], json.loads(converted[EXT+"/examples/bank.json"])["id"])
        for entry in files:
            if entry.startswith("extensions/") and entry != EXT+"/examples/bank.json":
                self.assertEqual(files[entry], converted[entry])
        self.assertEqual([EXT+"/examples/bank.json"], [entry["path"] for entry in report["examples"]])
        self.assertTrue(any("重新审核" in warning for warning in report["warnings"]))
        self.assertNotIn("synthetic", json.dumps(report))

    def test_same_input_has_same_uuid_and_identical_output_zip(self):
        self.write(fixture())
        a = tool.upgrade(self.source, self.output)
        second = self.root/"again.qbank"
        b = tool.upgrade(self.source, second)
        self.assertEqual(a, b)
        self.assertEqual(self.output.read_bytes(), second.read_bytes())

    def test_distinct_input_changes_uuid_and_multiple_banks_are_unique(self):
        files = fixture()
        files[BANK+"2/bank.json"] = files[BANK+"/bank.json"]
        report, _ = self.convert(files)
        ids = [entry["uuid"] for entry in report["banks"]+report["examples"]]
        self.assertEqual(len(ids), len(set(ids)))
        edit(files, BANK+"/bank.json", lambda bank: bank.update(description="new metadata"))
        self.write(files)
        other = tool.upgrade(self.source, self.root/"other.qbank")
        self.assertNotEqual(report["banks"][0]["uuid"], other["banks"][0]["uuid"])

    def test_single_bank_scope_preserved(self):
        report, converted = self.convert(fixture("bank"))
        self.assertEqual("bank", report["scope"])
        self.assertEqual("bank", json.loads(converted[tool.MARKER])["scope"])

    def test_omitted_bank_version_is_documented_legacy_one(self):
        files = fixture()
        edit(files, BANK+"/bank.json", lambda bank: bank.pop("formatVersion"))
        self.convert(files)

    def test_parts_default_and_alternating_extension_groups_keep_question_order(self):
        files = fixture()
        other = EXT+"乙"
        for path, raw in list(files.items()):
            if path.startswith(EXT+"/"):
                files[path.replace(EXT+"/", other+"/")] = raw.replace(EXT.encode(), other.encode())
        edit(files, other+"/manifest.json", lambda m: m.update(name="同名组"))
        edit(files, EXT+"/manifest.json", lambda m: m.update(name="同名组"))
        questions = [question("z", outline={"level": "parts", "items": [{"id": "stable", "label": "甲"}]}),
                     question("a"), question("b", extension={"path": other}), question("c")]
        edit(files, BANK+"/bank.json", lambda bank: bank.update(questions=questions))
        _, out = self.convert(files)
        outline = json.loads(out[BANK+"/bank.json"])["outline"]
        self.assertEqual(["同名组"]*3, [g["group"] for g in outline])
        self.assertEqual(["z", "a", "b", "c"], [q["question"] for g in outline for q in g["items"]])
        self.assertEqual([{"id": "stable", "label": "甲"}], outline[0]["items"][0]["parts"])
        self.assertEqual({"question": "a"}, outline[0]["items"][1])

    def test_media_audio_moves_and_only_host_file_reference_changes(self):
        files = fixture()
        for name, data in [("part.mp3", b"ID3example"), ("part.m4a", b"\0\0\0\x18ftypM4A ")]:
            files[BANK+"/media/"+name] = data
        listening = {"materials": [{"id": "a", "title": "示例音频", "file": "media/part.mp3"}], "examMaterialId": "a"}
        edit(files, BANK+"/bank.json", lambda b: b.update(listening=listening))
        _, out = self.convert(files)
        self.assertNotIn(BANK+"/media/part.mp3", out)
        self.assertEqual(b"ID3example", out[BANK+"/assets/part.mp3"])
        self.assertEqual("assets/part.mp3", json.loads(out[BANK+"/bank.json"])["listening"]["materials"][0]["file"])

    def test_images_keep_hashes_names_and_bytes(self):
        files = fixture()
        sha = hashlib.sha256(PNG).hexdigest()
        files[BANK+"/assets/中文.png"] = PNG
        data = {"nested": [{"type": "image", "attrs": {"assetId": sha}}]}
        edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(data=data))
        _, out = self.convert(files)
        self.assertEqual(PNG, out[BANK+"/assets/中文.png"])
        self.assertEqual(data, json.loads(out[BANK+"/bank.json"])["questions"][0]["data"])

    def test_number_tokens_unicode_and_escapes_are_not_lost(self):
        files = fixture()
        edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(data="NUMBER_PLACEHOLDER"))
        raw = b'{"n":[-0,1.0,1e21,9007199254740993,0.1234567890123456789],"s":"\\u4e2d\\ud83d\\ude00\\n\\t\\\\\\\""}'
        files[BANK+"/bank.json"] = files[BANK+"/bank.json"].replace(b'"NUMBER_PLACEHOLDER"', raw)
        _, out = self.convert(files)
        for token in [b"[-0,1.0,1e21,9007199254740993,0.1234567890123456789]"]:
            self.assertIn(token, out[BANK+"/bank.json"])
        self.assertEqual("中😀\n\t\\\"", json.loads(out[BANK+"/bank.json"])["questions"][0]["data"]["s"])

    def test_unknown_fields_and_group_metadata_reject_with_location(self):
        for edit_at in ["root", "question", "outline"]:
            with self.subTest(edit_at=edit_at):
                files = fixture()
                def change(bank):
                    target = bank if edit_at == "root" else bank["questions"][0] if edit_at == "question" else bank["questions"][0]["outline"]
                    target["customGroup"] = "unrecognized"
                edit(files, BANK+"/bank.json", change)
                self.rejects(files, "customGroup")

    def test_wrong_package_and_bank_versions_reject(self):
        for value in [1, 2, 4, 3.0, "3", None]:
            with self.subTest(package=value):
                files = fixture(); edit(files, tool.MARKER, lambda m: m.update(formatVersion=value))
                self.rejects(files, tool.MARKER+".formatVersion")
        for value in [2, 1.0, "1", None]:
            with self.subTest(bank=value):
                files = fixture(); edit(files, BANK+"/bank.json", lambda b: b.update(formatVersion=value))
                self.rejects(files, ".formatVersion")

    def test_wrong_bindings_missing_files_and_examples_owner_reject(self):
        for change, expected in [(lambda b: b.update(extension={"id": "example", "version": "1.0.0"}), ".version"),
                                 (lambda b: b.update(extension={"path": EXT.upper()}), ".path"),
                                 (lambda b: b.update(extension={"path": EXT, "id": "wrong"}), ".id")]:
            files = fixture(); edit(files, BANK+"/bank.json", change); self.rejects(files, expected)
        files = fixture(); del files[EXT+"/rules.js"]; self.rejects(files, "rules.js")

    def test_outline_invalid_shape_label_parts_capability_or_duplicate_reject(self):
        values = [None, {"level": "unknown"}, {"level": "question", "label": " bad "},
                  {"level": "parts", "items": []}, {"level": "parts", "items": [{"id": "a", "label": "A"}]*2},
                  {"level": "question", "label": "A", "items": []}]
        for value in values:
            with self.subTest(outline=value):
                files = fixture(); edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(outline=value))
                self.rejects(files, ".outline")
        files = fixture()
        edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(outline={"level": "parts", "items": [{"id": "a", "label": "A"}]}))
        edit(files, EXT+"/manifest.json", lambda m: m["requiresApi"].update(capabilities=[]))
        self.rejects(files, ".outline")

    def test_extension_name_is_not_silently_trimmed_or_truncated_for_group(self):
        for name in ["n"*81, " x", "<x>"]:
            files = fixture(); edit(files, EXT+"/manifest.json", lambda m: m.update(name=name)); self.rejects(files, "manifest.json.name")

    def test_media_collision_missing_reference_and_unsupported_types_reject(self):
        files = fixture(); files[BANK+"/media/a.mp3"] = files[BANK+"/assets/A.mp3"] = b"ID3"; self.rejects(files, "media/a.mp3")
        for name, content in [("x.wav", b"RIFF\0\0WAVE"), ("x.mp3", PNG), ("x.png", b"executable"), ("x.mp4", b"\0\0\0\0ftyp")]:
            files = fixture(); files[BANK+"/assets/"+name] = content; self.rejects(files, name)
        files = fixture(); edit(files, BANK+"/bank.json", lambda b: b.update(listening={"materials": [{"id": "a", "title": "音频", "file": "media/missing.mp3"}]}))
        self.rejects(files, ".materials[0].file")

    def test_missing_image_or_unconvertible_data_types_reject_without_traceback(self):
        for value in ["0"*64, {}, []]:
            files = fixture(); edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(data={"type": "image", "attrs": {"assetId": value}}))
            self.rejects(files, "assetId")
        for field in ["materialId", "examMaterialId"]:
            files = fixture()
            edit(files, BANK+"/bank.json", lambda b: b.update(listening={"materials": [{"id": "a", "title": "音频", "file": "assets/a.mp3"}], **({field: []} if field == "examMaterialId" else {})}))
            files[BANK+"/assets/a.mp3"] = b"ID3"
            if field == "materialId":
                edit(files, BANK+"/bank.json", lambda b: b["questions"][0].update(listening={field: []}))
            self.rejects(files, field)

    def test_unsafe_duplicate_private_and_nested_paths_reject(self):
        for name in ["../escape", "/absolute", "question-banks//x", BANK+"/assets/../x.png", BANK+"/assets/x/a.png",
                     BANK+"/assets/CON.png", BANK+"/assets/end. ", BANK+"/.instance.json", "other/file", "quizforge-package.json/"]:
            with self.subTest(path=name):
                files = list(fixture().items())+[(name, b"x")]; self.rejects(files)
        for name in [BANK+"/bank.json", BANK+"/BANK.JSON"]:
            self.rejects(list(fixture().items())+[(name, b"{}")], "重复")

    def test_unknown_bank_file_and_multiple_group_reject(self):
        for name in [BANK+"/README.md", "question-banks/别组/题库/bank.json"]:
            files = fixture(); files[name] = b"{}"; self.rejects(files, name)

    def test_empty_directories_are_ignored_and_not_counted_as_groups(self):
        files = fixture(); files["question-banks/空组/empty/"] = b""; files[BANK+"/assets/empty/"] = b""
        report, out = self.convert(files)
        self.assertEqual(2, report["ignoredDirectoryEntries"])
        self.assertNotIn("question-banks/空组/empty/", out)

    def test_invalid_utf8_duplicate_json_key_nonfinite_or_trailing_json_reject(self):
        for raw in [b"\xff", b'{"formatVersion":1,"formatVersion":1}', b'{"n":NaN}', b'{} {}', b'{"n":1e9999}']:
            files = fixture(); files[BANK+"/bank.json"] = raw; self.rejects(files, BANK+"/bank.json")

    def test_small_custom_limits_bound_file_count_and_total(self):
        self.write(fixture())
        with patch.object(tool, "MAX_UPLOAD", 1):
            with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        for name in ["MAX_FILE", "MAX_TOTAL"]:
            with patch.object(tool, name, 1):
                with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_crc_corruption_and_non_zip_reject_without_partial_output(self):
        self.source.write_bytes(b"not a zip")
        with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        with zipfile.ZipFile(self.source, "w", zipfile.ZIP_STORED) as archive:
            for name, value in fixture().items(): archive.writestr(name, value)
        raw = bytearray(self.source.read_bytes())
        name_size, extra_size = struct.unpack_from("<HH", raw, 26)
        raw[30+name_size+extra_size] ^= 1  # data byte, not a header/name byte
        self.source.write_bytes(raw)
        with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_encryption_and_invalid_utf8_zip_names_are_controlled_errors(self):
        for mode in ["encrypted", "invalid-utf8", "undeclared-encoding"]:
            self.write(fixture()); raw = bytearray(self.source.read_bytes())
            central = raw.index(b"PK\x01\x02")
            flag = {"encrypted": 1, "invalid-utf8": 0x800, "undeclared-encoding": 0}[mode]
            struct.pack_into("<H", raw, 6, struct.unpack_from("<H", raw, 6)[0] | flag)
            struct.pack_into("<H", raw, central+8, struct.unpack_from("<H", raw, central+8)[0] | flag)
            if mode != "encrypted": raw[30] = raw[central+46] = 255
            self.source.write_bytes(raw)
            with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
            self.assertFalse(self.output.exists())

    def test_empty_zip_and_excessive_entry_count_reject(self):
        for count in [0, 10001]:
            with zipfile.ZipFile(self.source, "w") as archive:
                for index in range(count): archive.writestr(f"question-banks/g/b/assets/{index}.png", b"")
            with self.assertRaisesRegex(tool.UpgradeError, "条目数"): tool.upgrade(self.source, self.output)

    def test_duplicate_question_id_and_nonself_example_binding_reject(self):
        files = fixture(); edit(files, BANK+"/bank.json", lambda b: b["questions"].append(question()))
        self.rejects(files, ".questions[1].id")
        files = fixture(); other = EXT+"乙"
        for path, raw in list(files.items()):
            if path.startswith(EXT+"/"): files[path.replace(EXT+"/", other+"/")] = raw.replace(EXT.encode(), other.encode())
        edit(files, EXT+"/examples/bank.json", lambda b: b.update(extension={"path": other}))
        self.rejects(files, "examples/bank.json.extension.path")

    def test_example_audio_uses_same_conversion_and_preserves_audio_bytes(self):
        files = fixture(); root = EXT+"/examples"
        files[root+"/media/a.mp3"] = b"ID3example"
        edit(files, root+"/bank.json", lambda b: b.update(listening={"materials": [{"id": "a", "title": "示例", "file": "media/a.mp3"}]}))
        report, out = self.convert(files)
        self.assertEqual(b"ID3example", out[root+"/assets/a.mp3"])
        self.assertEqual("assets/a.mp3", json.loads(out[root+"/bank.json"])["listening"]["materials"][0]["file"])
        self.assertEqual(root+"/bank.json.listening.materials[0].file", report["rewrittenAudioReferences"][0]["location"])

    def test_manifest_unknown_fields_missing_metadata_and_invalid_scope_reject(self):
        for change, location in [(lambda m: m.update(custom=True), ".custom"), (lambda m: m.pop("id"), ".id"),
                                 (lambda m: m.update(version="bad version"), ".version")]:
            files = fixture(); edit(files, EXT+"/manifest.json", change); self.rejects(files, location)
        for scope in [[], {}, None, "unknown"]:
            files = fixture(); edit(files, tool.MARKER, lambda m: m.update(scope=scope)); self.rejects(files, ".scope")

    def test_symlink_zip_entry_rejects(self):
        self.write(fixture())
        with zipfile.ZipFile(self.source, "a") as archive:
            info = zipfile.ZipInfo(BANK+"/assets/link.png"); info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, "/etc/passwd")
        with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)

    def test_no_overwrite_even_at_atomic_publish_race(self):
        self.write(fixture()); self.output.write_bytes(b"existing")
        with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        self.assertEqual(b"existing", self.output.read_bytes()); self.output.unlink()
        real_link = tool.os.link
        def competing(src, target):
            Path(target).write_bytes(b"concurrent")
            return real_link(src, target)
        with patch.object(tool.os, "link", side_effect=competing):
            with self.assertRaises(tool.UpgradeError): tool.upgrade(self.source, self.output)
        self.assertEqual(b"concurrent", self.output.read_bytes())
        self.assertEqual([], list(self.root.glob(".qbank-upgrade-*")))

    def test_cli_report_and_failure_exit_codes(self):
        self.write(fixture())
        report = self.root/"report.json"
        args = [sys.executable, "-B", str(Path(tool.__file__)), str(self.source), str(self.output), "--report", str(report)]
        success = subprocess.run(args, capture_output=True, encoding="utf-8")
        self.assertEqual(0, success.returncode, success.stderr)
        self.assertEqual(json.loads(success.stdout), json.loads(report.read_text(encoding="utf-8")))
        failed = subprocess.run(args, capture_output=True, encoding="utf-8")
        self.assertEqual(2, failed.returncode)
        self.assertNotIn("Traceback", failed.stderr)


if __name__ == "__main__":
    unittest.main()
