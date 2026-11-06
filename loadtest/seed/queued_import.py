"""本机测试数据共用的上传、等待校验、批准样例、创建练习实例流程。"""
import time
import uuid
import urllib.parse


def upload_and_import(request, archive, filename, cookie, approve, *, timeout=180, pause=time.sleep):
    status, accepted, _ = request("/api/library/uploads?filename=" + urllib.parse.quote(filename), archive, cookie, "application/octet-stream")
    if status != 202:
        raise RuntimeError(f"上传失败 HTTP {status}: {accepted}")
    pack = str(uuid.UUID(accepted["packId"]))
    revision = accepted["revision"]
    deadline = time.monotonic() + timeout
    while True:
        status, detail, _ = request(f"/api/library/packs/{pack}", token=cookie)
        if status != 200:
            raise RuntimeError(f"查询题库失败 HTTP {status}: {detail}")
        version = next(row for row in detail["revisions"] if row["revision"] == revision)
        if version["status"] == "rejected":
            raise RuntimeError("后台校验未通过：" + str(version.get("reason")))
        if version["banks"]:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("等待题库后台校验超时")
        # 按实际任务状态轮询；不是用固定等待判断并发结果。
        pause(.1)
    approve(pack)
    status, imported, _ = request(f"/api/library/packs/{pack}/practice",
            {"revision": revision, "banks": [row["bankUuid"] for row in version["banks"]]}, cookie)
    if status != 200:
        raise RuntimeError(f"导入练习空间失败 HTTP {status}: {imported}")
    return imported
