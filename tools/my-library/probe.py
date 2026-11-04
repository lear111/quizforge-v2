"""仅本机：验证已校验的 300 题资源创建实例的耗时，不执行压测。"""
import argparse
import http.cookiejar
import json
import re
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.cookies import SimpleCookie
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError("探针不允许重定向")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8288")
    parser.add_argument("--project", default="qf-my-library")
    parser.add_argument("--count", type=int, default=30)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    target = urllib.parse.urlsplit(base)
    if target.scheme != "http" or target.hostname not in {"localhost", "127.0.0.1"} or target.path or target.username or target.password:
        parser.error("仅允许本机 HTTP 根地址")
    if not re.fullmatch(r"qf-my-library(?:-[a-z0-9-]+)?", args.project) or not 1 <= args.count <= 100:
        parser.error("仅允许本任务的独立 Compose，样本数为 1～100")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    cookie = None

    def sql(statement):
        return subprocess.check_output([
            "docker", "compose", "-p", args.project, "-f", str(ROOT / "deploy/docker-compose.yml"),
            "exec", "-T", "mysql", "sh", "-c",
            'exec mysql --batch --skip-column-names --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"',
        ], input=statement, text=True, stderr=subprocess.DEVNULL).strip()

    def request(path, body=None, mime="application/json"):
        nonlocal cookie
        if not path.startswith("/api/"):
            raise ValueError("仅允许本机 API")
        headers = {"Origin": base, "Content-Type": mime}
        if cookie:
            headers["Cookie"] = cookie
        data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=data, headers=headers)
        with opener.open(req, timeout=120) as response:
            if response.headers.get("Set-Cookie"):
                parsed = SimpleCookie(response.headers["Set-Cookie"])
                cookie = "; ".join(f"{key}={value.value}" for key, value in parsed.items())
            return json.load(response)

    previous = sql("SELECT registration_code FROM auth_policy WHERE id=1;")
    try:
        sql("UPDATE auth_policy SET registration_code=FALSE WHERE id=1;")
        password = uuid.uuid4().hex
        account = request("/api/auth/register", {"identifier": f"probe-{uuid.uuid4()}@example.test", "name": "本机导入计时", "password": password})
        user = account["user"]["id"]
        if not re.fullmatch(r"[a-f0-9-]{36}", user):
            raise RuntimeError("非预期用户 ID")
        sql(f"UPDATE account_user SET is_admin=TRUE WHERE id='{user}';")
    finally:
        sql(f"UPDATE auth_policy SET registration_code={int(previous)} WHERE id=1;")

    original = (ROOT / "samples/loadtest-300.qbank").read_bytes()
    accepted = request("/api/library/uploads?filename=loadtest-300.qbank", original, "application/octet-stream")
    pack = accepted["packId"]
    deadline = time.monotonic() + 120
    while True:
        detail = request(f"/api/library/packs/{pack}")["revisions"][0]
        if detail["status"] == "approved" and detail["banks"]:
            break
        if detail["status"] == "rejected" or time.monotonic() > deadline:
            raise RuntimeError(f"后台未完成：{detail['status']} {detail.get('reason')}")
        # 条件轮询只等异步准备完成，不用于判断并发或计时结果。
        time.sleep(0.2)
    bank = detail["banks"][0]
    if bank["questionCount"] != 300:
        raise RuntimeError("样例不是 300 题")
    latencies = []
    for index in range(args.count + 5):
        start = time.perf_counter()
        response = request(f"/api/library/packs/{pack}/practice", {"revision": 1, "banks": [bank["bankUuid"]]})
        elapsed = (time.perf_counter() - start) * 1000
        if len(response["collections"]) != 1:
            raise RuntimeError("实例数量不正确")
        if index >= 5:
            latencies.append(elapsed)
    ordered = sorted(latencies)
    print(json.dumps({"bankQuestions": 300, "warmup": 5, "samples": len(latencies),
                      "medianMs": statistics.median(latencies), "p95Ms": ordered[(len(ordered)*95+99)//100-1],
                      "minMs": min(latencies), "maxMs": max(latencies), "rawMs": latencies}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
