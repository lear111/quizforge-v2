import json
import unittest
from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlsplit

from exporter import Collector, Engine, labels

PROJECT = "qf-loadtest-unit"
ID = "a" * 64


def container(service="app", project=PROJECT, identifier=ID):
    return {"Id": identifier, "Labels": {"com.docker.compose.project": project,
                                          "com.docker.compose.service": service}}


class FakeEngine:
    def __init__(self, containers=None, cpu=None):
        self.paths = []
        self.containers = containers if containers is not None else [container()]
        self.cpu = cpu if cpu is not None else {
            "cpu_usage": {"total_usage": 2_500_000_000},
            "throttling_data": {"periods": 100, "throttled_periods": 25, "throttled_time": 750_000_000}}

    def get(self, path):
        self.paths.append(path)
        if path == "/version": return {"ApiVersion": "1.56"}
        if "/containers/json?" in path: return self.containers
        return {"cpu_stats": self.cpu}


class ExporterTest(unittest.TestCase):
    def test_cpu_and_cfs_counters_preserve_units_and_labels(self):
        engine = FakeEngine()
        body = Collector(PROJECT, engine).render()
        tag = f'{{project="{PROJECT}",service="app",id="{ID}"}}'
        for name, value in (("container_cpu_usage_seconds_total", "2.5"),
                            ("container_cpu_cfs_periods_total", "100"),
                            ("container_cpu_cfs_throttled_periods_total", "25"),
                            ("container_cpu_cfs_throttled_seconds_total", "0.75"),
                            ("qf_container_stats_throttling_available", "1")):
            self.assertIn(f"{name}{tag} {value}\n", body)
        query = parse_qs(urlsplit(engine.paths[1]).query)
        self.assertEqual({"label": ["com.docker.compose.project=" + PROJECT]}, json.loads(query["filters"][0]))
        self.assertTrue(engine.paths[-1].endswith("/stats?stream=false&one-shot=true"))

    def test_only_four_services_in_selected_project_are_read(self):
        candidates = [container(service, identifier=str(i) * 64)
                      for i, service in enumerate(("app", "mysql", "nginx", "rule-runner"), 1)]
        candidates += [container("prometheus"), container(project="another-project"), container(identifier="bad/id")]
        engine = FakeEngine(candidates)
        body = Collector(PROJECT, engine).render()
        self.assertEqual(4, sum("/stats?" in p for p in engine.paths))
        self.assertNotIn('service="prometheus"', body)
        self.assertNotIn("another-project", body)

    def test_missing_cfs_is_reported_unavailable_not_fabricated_zero(self):
        body = Collector(PROJECT, FakeEngine(cpu={"cpu_usage": {"total_usage": 0}})).render()
        samples = [line for line in body.splitlines() if not line.startswith("#")]
        self.assertFalse(any(line.startswith("container_cpu_cfs_") for line in samples))
        self.assertTrue(any(line.startswith("qf_container_stats_throttling_available") and line.endswith(" 0") for line in samples))
        self.assertTrue(any(line.startswith("container_cpu_usage_seconds_total") and line.endswith(" 0") for line in samples))

    def test_counter_resets_are_passed_through_for_prometheus_rate(self):
        engine = FakeEngine()
        collector = Collector(PROJECT, engine)
        collector.render()
        engine.cpu["throttling_data"]["periods"] = 0
        self.assertRegex(collector.render(), r'container_cpu_cfs_periods_total\{[^\n]+\} 0\n')

    def test_stats_failure_keeps_other_service_and_omits_failed_counters(self):
        engine = FakeEngine([container(), container("mysql", identifier="b" * 64)])
        original = engine.get
        def get(path):
            if ID + "/stats?" in path: raise OSError("private socket detail")
            return original(path)
        engine.get = get
        body = Collector(PROJECT, engine).render()
        self.assertRegex(body, r'qf_container_stats_up\{[^\n]+service="app"[^\n]+\} 0')
        self.assertRegex(body, r'container_cpu_usage_seconds_total\{[^\n]+service="mysql"[^\n]+\} 2.5')
        self.assertNotIn("private socket detail", body)

    def test_discovery_failure_is_not_silently_successful(self):
        engine = Mock()
        engine.get.side_effect = OSError("private detail")
        body = Collector(PROJECT, engine).render()
        self.assertIn(f'qf_container_collector_up{{project="{PROJECT}"}} 0', body)
        self.assertNotIn("private detail", body)

    def test_invalid_counters_are_omitted(self):
        for value in (-1, float("nan"), float("inf"), "123", True, None):
            with self.subTest(value=value):
                engine = FakeEngine()
                engine.cpu["throttling_data"]["periods"] = value
                body = Collector(PROJECT, engine).render()
                self.assertNotRegex(body, r'(?m)^container_cpu_cfs_periods_total\{')

    def test_project_is_mandatory_and_restricted(self):
        for project in ("", "quizforge", "qf-loadtest/remote", "qf-loadtest\n"):
            with self.subTest(project=project), self.assertRaises(ValueError):
                Collector(project)

    def test_prometheus_labels_are_escaped(self):
        self.assertEqual('{x="a\\\\b\\\"c\\nd"}', labels({"x": 'a\\b"c\nd'}))

    def test_engine_uses_get_and_closes_connection(self):
        with patch("exporter.UnixConnection") as factory:
            connection = factory.return_value
            connection.getresponse.return_value.status = 200
            connection.getresponse.return_value.read.return_value = b'{"ApiVersion":"1.56"}'
            self.assertEqual({"ApiVersion": "1.56"}, Engine().get("/version"))
            connection.request.assert_called_once_with("GET", "/version")
            connection.close.assert_called_once()

    def test_engine_closes_on_http_failure(self):
        with patch("exporter.UnixConnection") as factory:
            connection = factory.return_value
            connection.getresponse.return_value.status = 500
            with self.assertRaises(RuntimeError): Engine().get("/version")
            connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
