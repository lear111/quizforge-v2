"""从仓库合成单选样例生成测试包；不读取真实题库、不下载音频。"""
import base64
import io
import json
from pathlib import Path
import sys
import uuid
import zipfile

origin, key = sys.argv[1:]
root = Path(__file__).resolve().parents[3]
with zipfile.ZipFile(root / "samples/single-choice.qbank") as source:
    files = {name: source.read(name) for name in source.namelist() if not name.endswith("/")}
path = next(p for p in files if p.startswith("question-banks/") and p.endswith("/bank.json"))
bank = json.loads(files[path])
bank["id"] = str(uuid.uuid4())
bank["title"] = "网络听力验收"
materials = [{"id": name.replace(".", "-"), "title": name, "url": origin+"/audio/"+name+"?key="+key} for name in
             ["good.wav", "gated.wav", "no-length.wav", "unavailable", "retry.wav", "no-cors.wav", "not-audio", "large-header", "large-body"]]
materials.append({"id": "local", "title": "本地材料", "file": "assets/local.mp3"})
bank["listening"] = {"materials": materials, "examMaterialId": "good-wav"}
question = bank["questions"][0]
question["listening"] = {"materialId": "good-wav"}
files[path] = json.dumps(bank, ensure_ascii=False).encode()
files[path.removesuffix("bank.json")+"assets/local.mp3"] = b"ID3synthetic-local-audio"
out = io.BytesIO()
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
    for name, data in files.items():
        archive.writestr(name, data)
print(json.dumps({"archive": base64.b64encode(out.getvalue()).decode(), "qid": question["id"], "answer": {"selectedOptionId": question["data"]["correctOptionId"]}, "key": key}))
