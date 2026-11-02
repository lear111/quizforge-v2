"""仅在本机独立 Compose 中比较 300 题题库的草稿 HTTP P95，不作容量结论。"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "loadtest/seed"))
from seed import api_client, local_url, require
from answers import sample_banks


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--base-url", type=local_url, default="http://127.0.0.1:8106")
    cli.add_argument("--project", default="qf-draft-outline")
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    if not args.project.startswith("qf-draft-outline"):
        cli.error("只允许独立的 qf-draft-outline* 项目")
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    engine = os.environ.get("DOCKER_HOST", context["Endpoints"]["docker"]["Host"])
    if not engine.startswith(("npipe://", "unix://")):
        cli.error("只允许本机 Docker Engine")
    compose = ["docker", "compose", "-p", args.project, "-f", str(ROOT / "deploy/docker-compose.yml")]
    published = subprocess.check_output(compose + ["port", "nginx", "80"], text=True).strip()
    if published != f"127.0.0.1:{urlsplit(args.base_url).port or 80}":
        cli.error("必须使用该项目的回环端口")
    request = api_client(args.base_url)
    status, config, _ = request("/api/config")
    require(status, config)
    policy = config["authPolicy"]
    if policy["registrationCaptchaRequired"] or policy["invitationRequired"]:
        cli.error("隔离测试环境必须关闭真实人机验证与邀请码")
    email = "probe@" + secrets.token_hex(12) + ".example.test"
    registration = {"identifier": email, "name": "草稿进度探针", "password": secrets.token_urlsafe(24)}
    if policy["registrationCodeRequired"]:
        status, body, _ = request("/api/auth/email-code", {"identifier": email})
        require(status, body)
        marker = "[开发验证码] p***@" + email.split("@", 1)[1] + " 注册验证码："
        deadline = time.monotonic() + 15
        while True:
            logs = subprocess.check_output(compose + ["logs", "app", "--no-color", "--since", "1m"], encoding="utf-8")
            codes = re.findall(re.escape(marker) + r"(\d{6})", logs)
            if codes:
                registration["code"] = codes[-1]
                break
            if time.monotonic() > deadline:
                raise RuntimeError("未取得本次合成账号的假验证码")
            time.sleep(.1)
    status, body, headers = request("/api/auth/register", registration)
    require(status, body)
    cookie = headers["Set-Cookie"].split(";", 1)[0]
    archive = ROOT / "samples/loadtest-300.qbank"
    answers = next(iter(sample_banks(archive).values()))["answers"]
    boundary = "qf-" + secrets.token_hex(16)
    upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="loadtest-300.qbank"\r\nContent-Type: application/zip\r\n\r\n'.encode()
              + archive.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
    status, body, _ = request("/api/library/import", upload, cookie, "multipart/form-data; boundary=" + boundary)
    require(status, body)
    collection = str(uuid.UUID(body["collections"][0]["id"]))
    sql = ("INSERT INTO admin_extension_review(digest,extension_id,version,status) SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' "
           "FROM content_extension_package e JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id "
           f"WHERE c.id='{collection}' ON DUPLICATE KEY UPDATE status='approved';")
    subprocess.run(compose + ["exec", "-T", "mysql", "sh", "-c", 'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],
                   input=sql, text=True, capture_output=True, check=True)
    base = "/api/collections/bank/" + collection
    status, overview, _ = request(base, token=cookie)
    require(status, overview)
    assert len(overview["questions"]) == 300
    ids = [q["id"] for q in overview["questions"][:4]]
    # Node 客户端与 app 同在本机 Docker 网络，避免 Windows 发布端口的计时噪声。
    script = """
        let text='';for await(const chunk of process.stdin)text+=chunk;
        const input=JSON.parse(text),samples=[];
        await Promise.all(input.ids.map(async(id)=>{
          let revision=0;
          for(let i=0;i<160;i++){
            const start=performance.now();
            const response=await fetch('http://app:8080'+input.base+'/questions/'+id+'/actions',{
              method:'POST',redirect:'error',headers:{Cookie:input.cookie,Origin:input.origin,'Content-Type':'application/json'},
              body:JSON.stringify({requestId:crypto.randomUUID(),revision,action:'draft',data:{answer:input.answers[id]}}),
              signal:AbortSignal.timeout(15000)});
            const body=await response.json();
            if(response.status!==200||body.revision!==revision+1)throw Error('草稿断言失败，HTTP '+response.status);
            revision=body.revision;
            if(i>=10)samples.push({round:Math.floor((i-10)/50),ms:performance.now()-start});
          }
        }));console.log(JSON.stringify(samples));
    """
    output = subprocess.check_output(compose + ["exec", "-T", "rule-runner", "node", "--input-type=module", "-e", script],
            input=json.dumps({"base": base, "ids": ids, "answers": answers, "cookie": cookie, "origin": args.base_url}), text=True)
    samples = json.loads(output)
    result = {"vus": 4, "questions": 300, "samples": len(samples), "errors": 0, "rounds": []}
    for index in range(3):
        values = sorted(row["ms"] for row in samples if row["round"] == index)
        result["rounds"].append({"count": len(values), "p50Ms": values[math.ceil(len(values)*.5)-1],
                                 "p95Ms": values[math.ceil(len(values)*.95)-1]})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
