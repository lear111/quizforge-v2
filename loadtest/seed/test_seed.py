import argparse
import unittest
from seed import local_url

class LocalTargetTest(unittest.TestCase):
    def test_loopback_origins_are_accepted(self):
        for url in ["http://localhost:8099", "http://127.0.0.1:8088/", "http://[::1]:8088"]:
            with self.subTest(url=url): self.assertEqual(local_url(url), url.rstrip("/"))

    def test_remote_or_ambiguous_targets_are_rejected_before_network_access(self):
        for url in ["https://localhost:8099", "http://example.com", "http://192.168.1.2", "http://0.0.0.0",
                    "http://127.0.0.1.example.com", "http://localhost@evil.test", "http://user:pass@localhost",
                    "http://localhost/api", "http://localhost?target=remote", "http://localhost/#fragment"]:
            with self.subTest(url=url), self.assertRaises(argparse.ArgumentTypeError): local_url(url)

if __name__ == "__main__": unittest.main()
