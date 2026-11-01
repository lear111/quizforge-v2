"""仅在本机独立环境比较无任务与异步完成期间的单题提交延迟。

使用公开的 300 题样例和真实 HTTP，创建临时用户，不读取业务数据或凭据。
只允许独立的 qf-practice-finish-* Compose 项目。
"""
import argparse
import concurrent.futures
import http.client
import json
import math
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import threading
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "loadtest/seed"))
from seed import api_client, local_url, require
from answers import sample_banks


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--base-url", type=local_url, default="http://127.0.0.1:8106")
    cli.add_argument("--project", default="qf-practice-finish-history-v4")
    cli.add_argument("--runs", type=int, default=3)
    cli.add_argument("--vus", type=int, default=4)
    cli.add_argument("--output", type=Path, default=ROOT / ".artifacts/finish-submit-latency.json")
    args = cli.parse_args()
    if not args.project.startswith("qf-practice-finish-") or not 1 <= args.runs <= 3 or not 1 <= args.vus <= 4:
        cli.error("use a dedicated qf-practice-finish-* stack, 1..3 paired runs and 1..4 VUs")
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    host = os.environ.get("DOCKER_HOST", context["Endpoints"]["docker"]["Host"])
    if not host.startswith(("npipe://", "unix://")):
        cli.error("local Docker Engine required")
    compose = ["docker", "compose", "-p", args.project, "-f", str(ROOT / "deploy/docker-compose.yml")]
    archive = ROOT / "samples/loadtest-300.qbank"
    template = next(iter(sample_banks(archive).values()))["answers"]
    question_ids = list(template)
    assert len(question_ids) == 300
    api = api_client(args.base_url)

    def register(label):
        status, body, headers = api("/api/auth/register", {"email": secrets.token_hex(8)+"@example.test", "name":label, "password":secrets.token_urlsafe(24)})
        require(status, body)
        return headers["Set-Cookie"].split(";", 1)[0]

    def upload(cookie):
        boundary = "qf-" + secrets.token_hex(16)
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="loadtest-300.qbank"\r\nContent-Type: application/zip\r\n\r\n'.encode()
                + archive.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
        status, body, _ = api("/api/library/import", data, cookie, "multipart/form-data; boundary="+boundary)
        require(status, body)
        collection = body["collections"][0]["id"]
        # 只在隔离数据库中批准本次公开样例使用的拓展。
        import uuid
        uuid.UUID(collection)
        sql = ("INSERT INTO admin_extension_review(digest,extension_id,version,status) SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' "
               "FROM content_extension_package e JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id "
               f"WHERE c.id='{collection}' ON DUPLICATE KEY UPDATE status='approved';")
        subprocess.run(compose+["exec", "-T", "mysql", "sh", "-c", 'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'], input=sql, text=True, capture_output=True, check=True)
        return "/api/collections/bank/"+collection

    def action(cookie, base, qid, name):
        status, body, _ = api(base+"/questions/"+qid+"/actions", {"requestId":secrets.token_hex(16), "revision":0, "action":name, "data":{"answer":template[qid]}}, cookie)
        require(status, body)
        return body

    def submit_phase(cookie, base, ids):
        # 每个虚拟用户复用 HTTP/1.1 连接，与浏览器/k6 相同，排除本机端口转发的新连接噪声。
        local=threading.local();connections=[];origin=urllib.parse.urlsplit(args.base_url)
        def init():
            local.connection=http.client.HTTPConnection(origin.hostname,origin.port or 80,timeout=180)
            connections.append(local.connection)
        def one(qid):
            start=time.perf_counter()
            local.connection.request("POST",base+"/questions/"+qid+"/actions",
                json.dumps({"requestId":secrets.token_hex(16),"revision":0,"action":"submit","data":{"answer":template[qid]}}),
                {"Cookie":cookie,"Origin":args.base_url,"Content-Type":"application/json"})
            response=local.connection.getresponse();body=json.loads(response.read());require(response.status,body)
            assert body["state"]["status"] == "submitted"
            assert body["state"]["result"]["score"] == body["state"]["result"]["maxScore"]
            return (time.perf_counter()-start)*1000
        try:
            with concurrent.futures.ThreadPoolExecutor(args.vus,initializer=init) as pool:
                return list(pool.map(one, ids))
        finally:
            for connection in connections:connection.close()

    def poll(cookie, base, job):
        status, body, _ = api(base+"/jobs/"+job,token=cookie);require(status,body)
        if body["status"] == "failed": raise RuntimeError(str(body["error"]))
        return body

    print(json.dumps({"phase":"预热 300 次真实单题提交"},ensure_ascii=False),flush=True)
    warmup=register("单题预热探针")
    submit_phase(warmup,upload(warmup),question_ids)
    foreground, background = register("单题延迟探针"), register("后台完成探针")
    results=[]
    for run in range(args.runs):
        print(json.dumps({"run": run+1, "phase": "准备公开样例与草稿"},ensure_ascii=False),flush=True)
        fg, bg = upload(foreground), upload(background)
        for qid in question_ids: action(background,bg,qid,"draft")
        submit_phase(foreground,fg,question_ids[:30])
        baseline=submit_phase(foreground,fg,question_ids[30:120])
        status, summary, _ = api(bg+"/summary",token=background);require(status,summary)
        status, accepted, _ = api(bg+"/finish", {"requestId":secrets.token_hex(16),"roundId":summary["roundId"],"summaryVersion":summary["summaryVersion"]},background)
        require(status,accepted,202);job=accepted["jobId"]
        until=time.monotonic()+600
        while True:
            before=poll(background,bg,job)
            if before["completed"]>0:break
            if time.monotonic()>until:raise TimeoutError("background did not progress")
            time.sleep(.05)
        during=submit_phase(foreground,fg,question_ids[120:210])
        after=poll(background,bg,job)
        assert after["status"] in ("queued","running"), "background must remain active throughout measurement"
        assert after["completed"]>before["completed"], "background must actually grade during the measurement"
        while poll(background,bg,job)["status"] != "completed":
            if time.monotonic()>until:raise TimeoutError("background did not finish")
            time.sleep(.1)
        post=submit_phase(foreground,fg,question_ids[210:300])
        result={"run":run+1,"baselineMs":baseline,"duringMs":during,"afterMs":post,
                "backgroundProgress":[before["completed"],after["completed"]],"errors":0}
        results.append(result)
        print(json.dumps({"run":run+1,"p95Ms":{key:round(percentile(value,.95),3) for key,value in (("baseline",baseline),("during",during),("after",post))},"backgroundProgress":result["backgroundProgress"]}),flush=True)
    report={"foregroundVus":args.vus,"backgroundWorkers":1,"backgroundIntervalMs":250,"questionsPerBackgroundJob":300,"initialWarmupSubmits":300,"warmupSubmitsPerRun":30,"runs":results}
    report["aggregateP95Ms"]={key:round(percentile([v for run in results for v in run[key+"Ms"]],.95),3) for key in ("baseline","during","after")}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps(report["aggregateP95Ms"]))


if __name__ == "__main__": main()
