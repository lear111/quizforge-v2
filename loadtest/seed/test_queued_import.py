import unittest
from queued_import import upload_and_import


class QueuedImportTest(unittest.TestCase):
    def test_waits_for_validation_then_approves_and_imports_without_multipart(self):
        pack = "00000000-0000-4000-8000-000000000001"
        calls, approvals, pauses = [], [], []
        def request(path, body=None, token=None, content_type="application/json"):
            calls.append((path, body, token, content_type))
            if len(calls) == 1:
                return 202, {"packId": pack, "revision": 2}, {}
            if len(calls) == 2:
                return 200, {"revisions": [{"revision": 2, "status": "pending", "banks": []}]}, {}
            if len(calls) == 3:
                return 200, {"revisions": [{"revision": 2, "status": "pending", "banks": [{"bankUuid": "author-id"}]}]}, {}
            return 200, {"collections": [{"id": "instance"}]}, {}
        result = upload_and_import(request, b"zip", "中文.qbank", "session", approvals.append, pause=pauses.append)
        self.assertEqual(result["collections"][0]["id"], "instance")
        self.assertEqual(approvals, [pack])
        self.assertEqual(pauses, [.1])
        self.assertEqual(calls[0][1:], (b"zip", "session", "application/octet-stream"))
        self.assertIn("filename=%E4%B8%AD%E6%96%87.qbank", calls[0][0])
        self.assertEqual(calls[-1][1], {"revision": 2, "banks": ["author-id"]})

    def test_rejected_upload_does_not_approve_or_create_instances(self):
        calls = []
        def request(path, *args, **kwargs):
            calls.append(path)
            if len(calls) == 1:
                return 202, {"packId": "00000000-0000-4000-8000-000000000001", "revision": 1}, {}
            return 200, {"revisions": [{"revision": 1, "status": "rejected", "reason": "旧格式", "banks": []}]}, {}
        with self.assertRaisesRegex(RuntimeError, "旧格式"):
            upload_and_import(request, b"zip", "test.qbank", "session", lambda _: self.fail("不能批准"))
        self.assertEqual(len(calls), 2)

    def test_upload_failure_is_reported_before_polling(self):
        with self.assertRaisesRegex(RuntimeError, "413"):
            upload_and_import(lambda *args: (413, {"error": "limit"}, {}), b"zip", "test.qbank", "session", lambda _: None)

    def test_timeout_is_bounded_and_does_not_create_instances(self):
        def request(path, *args, **kwargs):
            if path.startswith("/api/library/uploads?"):
                return 202, {"packId": "00000000-0000-4000-8000-000000000001", "revision": 1}, {}
            return 200, {"revisions": [{"revision": 1, "status": "pending", "banks": []}]}, {}
        with self.assertRaisesRegex(RuntimeError, "超时"):
            upload_and_import(request, b"zip", "test.qbank", "session", lambda _: self.fail("不能批准"), timeout=0)
