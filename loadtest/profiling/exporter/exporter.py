"""Local Compose CPU counters from Docker Engine's read-only stats endpoints.

No third-party packages; only GET /version, /containers/json and /.../stats.
The socket mount being :ro does NOT make the Engine API read-only. Keep this
small trusted exporter private; never expose the socket or this port publicly.
"""
import concurrent.futures
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
import re
import socket
from urllib.parse import urlencode

SERVICES = frozenset(("app", "mysql", "rule-runner", "nginx"))
COUNTERS = (
    ("container_cpu_usage_seconds_total", "CPU time in seconds.", ("cpu_usage", "total_usage"), 1e9),
    ("container_cpu_cfs_periods_total", "Elapsed CFS enforcement periods.", ("throttling_data", "periods"), 1),
    ("container_cpu_cfs_throttled_periods_total", "CFS periods with throttling.", ("throttling_data", "throttled_periods"), 1),
    ("container_cpu_cfs_throttled_seconds_total", "Cumulative throttled time in seconds.", ("throttling_data", "throttled_time"), 1e9),
)


class UnixConnection(http.client.HTTPConnection):
    def __init__(self):
        super().__init__("localhost", timeout=2)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


class Engine:
    def get(self, path):
        connection = UnixConnection()
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            if response.status != 200:
                raise RuntimeError("Engine GET failed")
            return json.loads(response.read())
        finally:
            connection.close()


def labels(values):
    def escape(value):
        return str(value).replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return "{" + ",".join(f'{key}="{escape(value)}"' for key, value in values.items()) + "}"


def number(cpu, path):
    value = cpu
    for part in path:
        if not isinstance(value, dict):
            return None
        value = value.get(part)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        return None
    return value


class Collector:
    def __init__(self, project, engine=None):
        if not re.fullmatch(r"qf-loadtest[a-z0-9_-]*", project):
            raise ValueError("Only a disposable local qf-loadtest project is allowed")
        self.project = project
        self.engine = engine or Engine()

    def render(self):
        lines = []
        for name, help_text, _, _ in COUNTERS:
            lines += [f"# HELP {name} {help_text}", f"# TYPE {name} counter"]
        lines += ["# HELP qf_container_stats_up Whether the service stats request succeeded.",
                  "# TYPE qf_container_stats_up gauge",
                  "# HELP qf_container_stats_throttling_available Whether all CFS counters are available.",
                  "# TYPE qf_container_stats_throttling_available gauge",
                  "# HELP qf_container_collector_up Whether Engine discovery succeeded.",
                  "# TYPE qf_container_collector_up gauge"]
        project_labels = {"project": self.project}
        try:
            version = self.engine.get("/version")["ApiVersion"]
            if not re.fullmatch(r"\d+\.\d+", version):
                raise ValueError("Invalid Engine API version")
            prefix = "/v" + version
            query = urlencode({"filters": json.dumps({"label": ["com.docker.compose.project=" + self.project]})})
            containers = self.engine.get(prefix + "/containers/json?" + query)
            # Recheck project locally, even though discovery already filters by label.
            selected = [c for c in containers
                        if c.get("Labels", {}).get("com.docker.compose.project") == self.project
                        and c["Labels"].get("com.docker.compose.service") in SERVICES
                        and re.fullmatch(r"[a-f0-9]{64}", c.get("Id", ""))]
            lines.append(f"qf_container_collector_up{labels(project_labels)} 1")
        except (OSError, ValueError, KeyError, TypeError, RuntimeError, http.client.HTTPException):
            lines.append(f"qf_container_collector_up{labels(project_labels)} 0")
            return "\n".join(lines) + "\n"

        def sample(container):
            service = container["Labels"]["com.docker.compose.service"]
            tag = labels({**project_labels, "service": service, "id": container["Id"]})
            try:
                body = self.engine.get(prefix + "/containers/" + container["Id"] + "/stats?stream=false&one-shot=true")
                cpu = body["cpu_stats"]
                if not isinstance(cpu, dict) or number(cpu, COUNTERS[0][2]) is None:
                    raise ValueError("Missing CPU counters")
                values = [f"qf_container_stats_up{tag} 1"]
                has_cfs = all(number(cpu, path) is not None for _, _, path, _ in COUNTERS[1:])
                values.append(f"qf_container_stats_throttling_available{tag} {int(has_cfs)}")
                for name, _, path, divisor in COUNTERS:
                    value = number(cpu, path)
                    if value is not None:
                        values.append(f"{name}{tag} {value / divisor:.12g}")
                return values
            except (OSError, ValueError, KeyError, TypeError, RuntimeError, http.client.HTTPException):
                return [f"qf_container_stats_up{tag} 0", f"qf_container_stats_throttling_available{tag} 0"]

        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            for values in executor.map(sample, selected):
                lines.extend(values)
        return "\n".join(lines) + "\n"


def main():
    collector = Collector(os.environ.get("COMPOSE_PROJECT", ""))

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/metrics":
                self.send_error(404)
                return
            body = collector.render().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    ThreadingHTTPServer(("0.0.0.0", 9404), Handler).serve_forever()


if __name__ == "__main__":
    main()
