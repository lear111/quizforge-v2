"""Opt-in serial HTTP timing of one draft in a disposable local 300-question bank.

Seed with --output loadtest/artifacts/lazy-users.json first. Run before/after
changing only the app image; keep the database and fixtures. No concurrency.
"""
import argparse
import json
from pathlib import Path
import statistics
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "loadtest/seed"))
from seed import api_client, local_url, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", type=local_url, default="http://127.0.0.1:8100")
    parser.add_argument("--users-file", type=Path, default=ROOT / "loadtest/artifacts/lazy-users.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    user = json.loads(args.users_file.read_text(encoding="utf-8"))[0]
    if not user["email"].startswith("loadtest-"):
        parser.error("only disposable loadtest users are allowed")
    request = api_client(args.base_url)  # Loopback only, no proxy or redirects.
    status, body, headers = request("/api/auth/login", {"email": user["email"], "password": user["password"]})
    require(status, body)
    cookie = headers["Set-Cookie"].split(";", 1)[0]
    try:
        bank = next(b for b in user["collections"] if b["sample"] == "loadtest-300.qbank")
        assert len(bank["questionIds"]) == 300
        qid = bank["questionIds"][0]
        path = f'/api/collections/bank/{bank["id"]}/questions/{qid}'
        status, body, _ = request(path, token=cookie)
        require(status, body)
        revision = body["state"]["revision"]
        assert body["state"]["status"] != "submitted"
        times = []
        for i in range(80):
            payload = {"action": "draft", "requestId": str(uuid.uuid4()), "revision": revision,
                       "data": {"answer": bank["answers"][qid]}}
            start = time.perf_counter_ns()
            status, body, _ = request(path + "/actions", payload, cookie)
            elapsed = (time.perf_counter_ns() - start) / 1e6
            require(status, body)
            assert body["revision"] == revision + 1
            assert body["state"]["answer"] == bank["answers"][qid]
            revision += 1
            if i >= 50: times.append(elapsed)
        result = {"questionCount": 300, "warmups": 50, "samples": len(times),
                  "collectionId": bank["id"], "questionId": qid, "finalRevision": revision,
                  "meanMs": statistics.mean(times), "medianMs": statistics.median(times),
                  "p95Ms": sorted(times)[28], "minMs": min(times), "maxMs": max(times), "timesMs": times}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps({k: v for k, v in result.items() if k not in ("timesMs", "collectionId", "questionId")}))
    finally:
        status, body, _ = request("/api/auth/logout", b"", cookie)
        require(status, body, 204)


if __name__ == "__main__":
    main()
