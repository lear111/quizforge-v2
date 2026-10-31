"""Local-only repeatable fixtures. No direct business inserts: only sample review approval uses SQL."""
import argparse, ipaddress, json, os, secrets, subprocess, urllib.request, urllib.error, urllib.parse
from pathlib import Path
from answers import sample_banks

ROOT = Path(__file__).resolve().parents[2]

def local_url(value):
    url = urllib.parse.urlsplit(value)
    try:
        local = url.hostname == "localhost" or ipaddress.ip_address(url.hostname).is_loopback
    except ValueError:
        local = False
    if not local or url.scheme != "http" or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment:
        raise argparse.ArgumentTypeError("base URL must be a local loopback HTTP origin")
    return value.rstrip("/")

def api_client(base_url):
    base_url = local_url(base_url)
    # No proxies or redirects: requests must remain on the local origin.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused): return None
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    def request(path, body=None, token=None, content_type="application/json"):
        headers = {"Content-Type": content_type, "Origin": base_url}
        if token: headers["Cookie"] = token
        if body is not None and not isinstance(body, bytes): body = json.dumps(body).encode()
        req = urllib.request.Request(base_url + path, data=body, headers=headers)
        try:
            with client.open(req, timeout=180) as response:
                return response.status, (None if response.status == 204 else json.loads(response.read())), response.headers
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read()), error.headers
    return request


def require(status, body, expected=200):
    if status != expected: raise RuntimeError(f"HTTP {status}: {body}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", type=local_url, default="http://localhost:8088")
    parser.add_argument("--project", default="qf-loadtest")
    parser.add_argument("--users", type=int, default=100)
    parser.add_argument("--output", type=Path, default=ROOT / "loadtest/users.json")
    args = parser.parse_args()
    if args.users < 1 or not args.project.startswith("qf-loadtest"):
        parser.error("users must be positive; project must start with qf-loadtest")
    # Refuse remote Docker contexts and require the disposable loadtest-labelled MySQL.
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    host = os.environ.get("DOCKER_HOST", context["Endpoints"]["docker"]["Host"])
    if not host.startswith(("npipe://", "unix://")):
        parser.error("use a local Unix socket or Windows named-pipe Docker engine")
    compose = ["docker", "compose", "-p", args.project, "-f", str(ROOT / "deploy/docker-compose.yml"),
               "-f", str(ROOT / "deploy/docker-compose.loadtest.yml")]
    container = subprocess.check_output(compose + ["ps", "-q", "mysql"], text=True).strip()
    label = subprocess.check_output(["docker", "inspect", "--format", '{{index .Config.Labels "qf.loadtest"}}', container], text=True).strip()
    if label != "true":
        parser.error("MySQL must have the qf.loadtest=true label")
    published = subprocess.check_output(compose + ["port", "nginx", "80"], text=True).strip()
    if published != "127.0.0.1:" + str(urllib.parse.urlsplit(args.base_url).port or 80):
        parser.error("base URL port must match this project's loopback nginx mapping")
    request = api_client(args.base_url)
    state_file = args.output.with_name(args.output.stem + ".seed-state.json")
    existing = state_file if state_file.exists() else args.output
    old = json.loads(existing.read_text(encoding="utf-8")) if existing.exists() else []
    by_email = {u["email"]: u for u in old}
    users = []
    archives = {name: (ROOT / "samples" / name) for name in ("basic-types.qbank", "loadtest-300.qbank")}
    templates = {name: sample_banks(path) for name, path in archives.items()}
    def checkpoint():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temp = state_file.with_suffix(".tmp")
        temp.write_text(json.dumps(list(by_email.values()), ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(state_file)
    for i in range(args.users):
        email = f"loadtest-{i:04d}@example.test"
        previous = by_email.get(email)
        password = previous["password"] if previous else secrets.token_urlsafe(24)
        if previous:
            status, body, headers = request("/api/auth/login", {"email": email, "password": password})
        else:
            status, body, headers = request("/api/auth/register", {"email": email, "name": f"压测用户{i}", "password": password})
        if status == 401 and previous:
            status, body, headers = request("/api/auth/register", {"email": email, "name": f"压测用户{i}", "password": password})
        require(status, body)
        token = headers["Set-Cookie"].split(";", 1)[0]
        collections = previous.get("collections", []) if previous else []
        user = {"email": email, "password": password, "collections": collections}
        users.append(user)
        by_email[email] = user
        checkpoint()
        for filename, archive in archives.items():
            expected = templates[filename]
            current = [row for row in collections if row["title"] in expected]
            if (len(current) != len(expected) or {row["title"] for row in current} != set(expected)
                    or any(request(f'/api/collections/bank/{row["id"]}', token=token)[0] not in (200, 422) for row in current)):
                boundary = "qf-" + secrets.token_hex(16)
                upload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
                          + archive.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
                status, body, _ = request("/api/library/import", upload, token, "multipart/form-data; boundary=" + boundary)
                require(status, body)
                current = [{"id": row["id"], "title": row["title"]} for row in body["collections"]]
                if {row["title"] for row in current} != set(expected):
                    raise RuntimeError("Imported sample titles do not match package")
                collections[:] = [row for row in collections if row["title"] not in expected] + current
            for row in current: row["sample"] = filename
            # Preserve each completed import if the next archive fails or the process is interrupted.
            checkpoint()
        # Only digests referenced by these sample instances receive the test-only approval.
        import uuid
        ids = [str(uuid.UUID(row["id"])) for row in collections]
        sql = "INSERT INTO admin_extension_review (digest,extension_id,version,status,reviewer,reviewed_at) SELECT DISTINCT e.digest,e.extension_id,e.version,'approved','loadtest-fixture',NOW(3) FROM content_extension_package e JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id WHERE c.id IN (" + ",".join("'" + value + "'" for value in ids) + ") ON DUPLICATE KEY UPDATE status='approved',reviewer='loadtest-fixture',reviewed_at=NOW(3);"
        subprocess.run(compose + ["exec", "-T", "mysql", "sh", "-c", 'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'], input=sql, text=True, check=True, capture_output=True)
        for row in collections:
            status, body, _ = request(f'/api/collections/bank/{row["id"]}', token=token)
            require(status, body)
            row["questionIds"] = [q["id"] for q in body["questions"]]
            answers = templates[row["sample"]][row["title"]]["answers"]
            if set(row["questionIds"]) != set(answers):
                raise RuntimeError("Imported question IDs do not match sample outline")
            row["answers"] = answers
        status, body, _ = request("/api/auth/logout", b"", token)
        require(status, body, 204)
        print(f"Prepared {i + 1}/{args.users} users", flush=True)
    by_email.update({u["email"]: u for u in users})
    state_file.write_text(json.dumps(list(by_email.values()), ensure_ascii=False, indent=2), encoding="utf-8")
    args.output.write_text(json.dumps(users, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(users)} local users to {args.output}")

if __name__ == "__main__": main()
