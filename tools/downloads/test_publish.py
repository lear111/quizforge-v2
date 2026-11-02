import datetime
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import publish  # noqa: E402


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self.temp.name, "downloads")
        os.mkdir(self.dir)
        self.now = datetime.datetime(2026, 11, 1, 8, 0, tzinfo=datetime.timezone.utc)

    def tearDown(self):
        self.temp.cleanup()

    def installer(self, name, data):
        path = os.path.join(self.temp.name, name)
        with open(path, "wb") as output:
            output.write(data)
        return path

    def manifest(self):
        with open(os.path.join(self.dir, "manifest.json"), encoding="utf-8") as source:
            return json.load(source)

    def test_publish_writes_installer_and_manifest_with_hash(self):
        data = b"MZ" + b"x" * 5000
        item = publish.publish(self.dir, "windows", "1.2.0", self.installer("setup.exe", data), "正式版",
                               "修复了登录问题\r\n新增手写板", now=self.now)
        self.assertEqual(item["file"], "QuizForge-1.2.0-windows.exe")
        self.assertEqual(item["bytes"], len(data))
        self.assertEqual(item["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(item["notes"], "修复了登录问题\n新增手写板")
        self.assertEqual(item["updated"], "2026-11-01T08:00:00Z")
        with open(os.path.join(self.dir, item["file"]), "rb") as source:
            self.assertEqual(source.read(), data)
        self.assertEqual(self.manifest(), {"schemaVersion": 1, "items": [item]})
        self.assertEqual(sorted(os.listdir(self.dir)), ["QuizForge-1.2.0-windows.exe", "manifest.json"])

    def test_new_version_replaces_the_previous_file_after_the_manifest_is_saved(self):
        publish.publish(self.dir, "windows", "1.0.0", self.installer("a.exe", b"old"), now=self.now)
        publish.publish(self.dir, "android", "1.0.0", self.installer("a.apk", b"apk"), now=self.now)
        publish.publish(self.dir, "windows", "1.1.0", self.installer("b.exe", b"new"), now=self.now)
        files = [item["file"] for item in self.manifest()["items"]]
        self.assertEqual(files, ["QuizForge-1.1.0-windows.exe", "QuizForge-1.0.0-android.apk"])
        self.assertFalse(os.path.exists(os.path.join(self.dir, "QuizForge-1.0.0-windows.exe")))

    def test_invalid_input_leaves_directory_unchanged(self):
        publish.publish(self.dir, "windows", "1.0.0", self.installer("a.exe", b"old"), now=self.now)
        before = sorted(os.listdir(self.dir)), self.manifest()
        for args in [("windows", "1.0.0", self.installer("same.exe", b"x")),
                     ("windows", "../1", self.installer("bad.exe", b"x")),
                     ("windows", "2.0.0", self.installer("empty.exe", b"")),
                     ("ios", "2.0.0", self.installer("x.ipa", b"x")),
                     ("windows", "2.0.0", os.path.join(self.temp.name, "missing.exe"))]:
            with self.assertRaises(publish.PublishError):
                publish.publish(self.dir, *args, now=self.now)
        with self.assertRaises(publish.PublishError):
            publish.publish(self.dir, "windows", "3.0.0", self.installer("long.exe", b"x"), notes="字" * 2001)
        self.assertEqual((sorted(os.listdir(self.dir)), self.manifest()), before)

    def test_remove_and_unknown_manifest_entries(self):
        publish.publish(self.dir, "android", "1.0.0", self.installer("a.apk", b"apk"), now=self.now)
        self.assertEqual(publish.remove(self.dir, "android"), {"removed": True, "file": "QuizForge-1.0.0-android.apk"})
        self.assertEqual(self.manifest()["items"], [])
        self.assertEqual(publish.remove(self.dir, "android"), {"removed": False})
        with open(os.path.join(self.dir, "manifest.json"), "w", encoding="utf-8") as output:
            json.dump({"schemaVersion": 1, "items": [{"platform": "windows", "file": "../etc/passwd"}]}, output)
        with self.assertRaises(publish.PublishError):
            publish.read_manifest(self.dir)

    def test_concurrent_publish_is_refused_by_the_lock(self):
        open(os.path.join(self.dir, publish.LOCK), "w").close()
        with self.assertRaises(publish.PublishError):
            publish.publish(self.dir, "windows", "1.0.0", self.installer("a.exe", b"x"), now=self.now)

    def test_command_line_reports_errors_with_exit_code_2(self):
        self.assertEqual(publish.main(["publish", "--dir", self.dir, "--platform", "windows", "--version", "1.0.0",
                                       "--file", os.path.join(self.temp.name, "missing.exe")]), 2)
        self.assertEqual(publish.main(["show", "--dir", self.dir]), 0)


if __name__ == "__main__":
    unittest.main()
