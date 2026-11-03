"""性能夹具与本机边界检查，不运行压测、不启动容器。"""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('benchmark.py')
spec = importlib.util.spec_from_file_location('queue_benchmark', SCRIPT)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkFixtureTest(unittest.TestCase):
    def test_deterministic_eight_extension_multi_bank_format_four(self):
        content = benchmark.fixture(banks=2, questions=8)
        self.assertEqual(content, benchmark.fixture(banks=2, questions=8))
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            manifests = [name for name in archive.namelist() if name.endswith('/manifest.json')]
            banks = [name for name in archive.namelist() if name.startswith('question-banks/') and name.endswith('/bank.json')]
            self.assertEqual(8, len(manifests))
            self.assertEqual(2, len(banks))
            self.assertEqual(4, json.loads(archive.read('quizforge-package.json'))['formatVersion'])
            for name in banks:
                bank = json.loads(archive.read(name))
                self.assertEqual(8, len(bank['questions']))
                self.assertEqual(8, len(bank['outline'][0]['items']))

    def test_remote_origin_is_rejected_before_any_docker_or_http_work(self):
        result = subprocess.run([sys.executable, '-B', str(SCRIPT), '--base-url', 'https://example.com', '--mode', 'queued'],
                                capture_output=True)
        self.assertEqual(2, result.returncode)


if __name__ == '__main__':
    unittest.main()
