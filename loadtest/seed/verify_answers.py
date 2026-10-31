"""Opt-in local HTTP correctness test. Submits every answer for one disposable seeded user."""
import argparse
import json
import uuid
from pathlib import Path
from answers import sample_banks
from seed import ROOT, api_client, local_url, require


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", type=local_url, default="http://localhost:8088")
    parser.add_argument("--users-file", type=Path, default=ROOT / "loadtest/users.json")
    parser.add_argument("--user-index", type=int, default=0)
    args = parser.parse_args()
    users = json.loads(args.users_file.read_text(encoding="utf-8"))
    if not 0 <= args.user_index < len(users): parser.error("user index is out of range")
    user = users[args.user_index]
    if not user["email"].startswith("loadtest-"): parser.error("only disposable loadtest users are allowed")
    request = api_client(args.base_url)
    status, body, headers = request("/api/auth/login", {"email": user["email"], "password": user["password"]})
    require(status, body)
    cookie = headers["Set-Cookie"].split(";", 1)[0]
    drafts = submits = objective = 0
    templates = {name: sample_banks(ROOT / "samples" / name) for name in ("basic-types.qbank", "loadtest-300.qbank")}
    try:
        for collection in user["collections"]:
            expected = templates[collection["sample"]][collection["title"]]
            if set(collection["answers"]) != set(collection["questionIds"]):
                raise AssertionError("answers must cover exactly the visible question IDs")
            for qid in collection["questionIds"]:
                path = f'/api/collections/bank/{collection["id"]}/questions/{qid}'
                status, body, _ = request(path, token=cookie)
                require(status, body)
                if body["state"]["status"] == "submitted":
                    raise AssertionError("Use fresh fixtures: this test consumes all questions for the selected user")
                answer = collection["answers"][qid]
                revision = body["state"]["revision"]
                payload = {"requestId": str(uuid.uuid4()), "revision": revision, "action": "draft", "data": {"answer": answer}}
                status, draft, _ = request(path + "/actions", payload, cookie)
                require(status, draft)
                assert draft["state"]["answer"] == answer, (collection["title"], qid, "draft answer differs")
                assert draft["revision"] == revision + 1
                drafts += 1
                payload.update(requestId=str(uuid.uuid4()), revision=draft["revision"], action="submit")
                status, submitted, _ = request(path + "/actions", payload, cookie)
                require(status, submitted)
                assert submitted["state"]["status"] == "submitted"
                assert submitted["state"]["answer"] == answer
                result = submitted["state"]["result"]
                kind = expected["kinds"][qid]
                if kind == "objective":
                    assert result["gradingStatus"] == "graded"
                    assert result["score"] == result["maxScore"] == expected["scores"][qid], (collection["title"], qid, result)
                    objective += 1
                elif kind == "text":
                    assert result["gradingStatus"] == "graded" and result["score"] == 0
                else:
                    assert result["gradingStatus"] == "pending" and result["score"] is None
                submits += 1
            print(f'Verified {collection["title"]}: {len(collection["questionIds"])} draft + submit pairs', flush=True)
    finally:
        status, body, _ = request("/api/auth/logout", b"", cookie)
        require(status, body, 204)
    print(f"PASS: {drafts} drafts HTTP 200; {submits} submits HTTP 200; {objective} objective answers full score")


if __name__ == "__main__": main()
