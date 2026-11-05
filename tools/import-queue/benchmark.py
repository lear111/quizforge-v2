"""仅使用本机独立 Compose 的合成包测量，不接受远程地址或已有用户凭据。"""
import argparse
import copy
import io
import ipaddress
import json
import os
from pathlib import Path
import random
import re
import threading
from concurrent.futures import ThreadPoolExecutor
import math
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / '.artifacts/local/import-queue'


def fixture(banks=12, questions=96, variant=0):
    """五种基础题型衍生八个独立摘要；每本题库混用八种拓展，全部内容为公开样例。"""
    with zipfile.ZipFile(ROOT / 'samples/basic-types.qbank') as source:
        original = {name: source.read(name) for name in source.namelist()}
    names = ['单选题', '多选题', '判断题', '简答题', '简答题（答题区）', '单选题', '多选题', '判断题']
    result = {'quizforge-package.json': json.dumps(dict(format='quizforge-bank-group', formatVersion=4,
                                                       group='队列合成验收', scope='group')).encode()}
    templates = []
    for number, name in enumerate(names):
        before = f'extensions/基础题型/{name}/'
        after = f'extensions/队列合成验收/拓展-{number}/'
        for path, content in original.items():
            if not path.startswith(before):
                continue
            relative = path[len(before):]
            if relative == 'manifest.json':
                manifest = json.loads(content)
                manifest['id'] += f'-queue-{number}'
                manifest['name'] += f'（队列验收 {number}）'
                content = json.dumps(manifest, ensure_ascii=False).encode()
            elif relative.startswith('examples/') and relative.endswith('.json'):
                example = json.loads(content)
                if isinstance(example, dict) and 'questions' in example:
                    example['id'] = str(uuid.uuid5(uuid.NAMESPACE_URL, after + relative))
                    example['extension'] = {'path': after.rstrip('/')}
                    for question in example['questions']:
                        if 'extension' in question:
                            question['extension'] = {'path': after.rstrip('/')}
                    content = json.dumps(example, ensure_ascii=False).encode()
            result[after + relative] = content
        bank = json.loads(original[f'question-banks/基础题型/{name}/bank.json'])
        templates.append((after.rstrip('/'), bank['questions'][0]))
    rng = random.Random(26 + variant)
    for b in range(banks):
        rows = []
        for q in range(questions):
            extension, template = templates[q % len(templates)]
            row = copy.deepcopy(template)
            row['id'] = f'b{b}-q{q}'
            row['extension'] = {'path': extension}
            def expand(value):
                if isinstance(value, dict):
                    if value.get('type') == 'text' and isinstance(value.get('text'), str):
                        value['text'] = ''.join(rng.choices('abcdef0123456789', k=2048))
                        return True
                    return any(expand(child) for child in value.values())
                return isinstance(value, list) and any(expand(child) for child in value)
            if not expand(row['data']):
                raise ValueError('样例缺少可扩充的富文本节点')
            rows.append(row)
        bank = dict(formatVersion=2, id=str(uuid.uuid5(uuid.NAMESPACE_URL, f'qf-queue-benchmark/{b}')),
                    title=f'合成题库 {b}', questions=rows,
                    outline=[{'group': '合成分组', 'items': [{'question': row['id']} for row in rows]}])
        result[f'question-banks/队列合成验收/题库-{b}/bank.json'] = json.dumps(bank, ensure_ascii=False).encode()
        for path, content in original.items():
            if path.startswith('question-banks/') and '/assets/' in path:
                result[f'question-banks/队列合成验收/题库-{b}/assets/{path.split("/assets/",1)[1]}'] = content
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as archive:
        for path, content in sorted(result.items()):
            entry = zipfile.ZipInfo(path, date_time=(2026, 11, 2, 0, 0, 0))
            archive.writestr(entry, content)
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8188')
    parser.add_argument('--project', default='qf-import-queue')
    parser.add_argument('--mode', choices=['fixture', 'queued', 'cache', 'foreground', 'upload'], default='fixture')
    parser.add_argument('--repeats', type=int, default=3)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.base_url)
    try:
        local = url.hostname == 'localhost' or ipaddress.ip_address(url.hostname).is_loopback
    except ValueError:
        local = False
    if not local:
        parser.error('只允许本机环回地址')
    if url.scheme != 'http' or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        parser.error('只允许本机 HTTP 源')
    if not re.fullmatch(r'qf-import-queue(?:-[a-z0-9-]+)?', args.project):
        parser.error('必须使用本任务的独立 Compose 项目名')
    LOCAL.mkdir(parents=True, exist_ok=True)
    data = fixture()
    (LOCAL / 'synthetic-eight-extensions.qbank').write_bytes(data)
    if args.mode == 'fixture':
        print(json.dumps({'bytes': len(data), 'banks': 12, 'questionsPerBank': 96, 'extensions': 8}))
        return
    context = json.loads(subprocess.check_output(['docker', 'context', 'inspect'], text=True))[0]
    host = os.environ.get('DOCKER_HOST', context['Endpoints']['docker']['Host'])
    if not host.startswith(('npipe://', 'unix://')):
        parser.error('只允许本机 Docker 引擎')
    envfile = LOCAL / 'empty.env'
    envfile.touch(exist_ok=True)
    compose = ['docker', 'compose', '--env-file', str(envfile), '-p', args.project, '-f', str(ROOT / 'deploy/docker-compose.yml')]

    def sql(query):
        return subprocess.check_output(compose + ['exec', '-T', 'mysql', 'sh', '-c',
            'exec mysql --default-character-set=utf8mb4 -N -B --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],
            input=query, text=True, encoding='utf-8', stderr=subprocess.DEVNULL).strip()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused):
            return None
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    cookie = None

    def request(path, body=None, content_type='application/json', token=None):
        headers = {'Origin': args.base_url, 'Content-Type': content_type}
        if token or cookie:
            headers['Cookie'] = token or cookie
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
        start = time.perf_counter()
        with client.open(urllib.request.Request(args.base_url + path, data=body, headers=headers), timeout=180) as response:
            value = json.loads(response.read())
            return value, response.headers, (time.perf_counter()-start)*1000

    def metrics():
        command = "fetch('http://127.0.0.1:8090/v1/metrics/prometheus',{headers:{'X-Internal-Token':process.env.QF_RULE_RUNNER_TOKEN}}).then(r=>r.text()).then(t=>console.log(t))"
        value = subprocess.check_output(compose + ['exec', '-T', 'rule-runner', 'node', '-e', command], text=True)
        match = re.search(r'qf_runner_worker_stops_total\{reason="lru"\} (\d+)', value)
        return float(match.group(1)) if match else 0

    sql('UPDATE auth_policy SET registration_code=FALSE,registration_captcha=FALSE,invitation_required=FALSE;')
    email = f'queue-bench-{uuid.uuid4()}@example.test'
    registered, headers, _ = request('/api/auth/register', {'email': email, 'name': '本机合成验收', 'password': 'local-benchmark-password'})
    cookie = headers['Set-Cookie'].split(';', 1)[0]
    user = registered['user']['id']
    sql(f"UPDATE account_user SET is_admin=TRUE WHERE id='{user}';")
    if args.mode == 'upload':
        result = []
        for size in (256*1024, 1024*1024, 4*1024*1024, 16*1024*1024):
            payload = random.Random(size).randbytes(size)
            for unused in range(3):
                accepted, headers, elapsed = request('/api/library/uploads?filename=upload-boundary.qbank', payload, 'application/octet-stream')
                result.append(dict(bytes=size, clientMs=elapsed, serverMs=headers.get('Server-Timing')))
        (LOCAL / 'benchmark-upload.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result))
        return
    if args.mode == 'foreground':
        foreground, fg_headers, _ = request('/api/auth/register', {'email': f'foreground-{uuid.uuid4()}@example.test',
            'name': '前台对照用户', 'password': 'local-benchmark-password'})
        foreground_cookie = fg_headers['Set-Cookie'].split(';', 1)[0]
        original = (ROOT / 'samples/loadtest-300.qbank').read_bytes()
        accepted, _, _ = request('/api/library/uploads?filename=loadtest-300.qbank', original, 'application/octet-stream', foreground_cookie)
        pack = str(uuid.UUID(accepted['packId']))
        deadline = time.monotonic() + 180
        while True:
            detail, _, _ = request('/api/library/packs/' + pack, token=foreground_cookie)
            version = detail['revisions'][0]
            if version['banks']:
                break
            if version['status'] == 'rejected' or time.monotonic() > deadline:
                raise RuntimeError('前台样例后台校验失败')
            time.sleep(.1)
        sql("UPDATE admin_extension_review e JOIN catalog_pack_revision r ON r.pack_id='" + pack + "' SET e.status='approved' WHERE JSON_CONTAINS(r.extension_digests,JSON_QUOTE(LOWER(HEX(e.digest))));")
        imported, _, _ = request('/api/library/packs/' + pack + '/practice',
            {'revision': 1, 'banks': [row['bankUuid'] for row in version['banks']]}, token=foreground_cookie)
        bank = imported['collections'][0]
        import runpy
        answers = runpy.run_path(str(ROOT / 'loadtest/seed/answers.py'))['sample_banks'](ROOT / 'samples/loadtest-300.qbank')['loadtest-300']['answers']
        identifiers = list(answers)
        queued = [fixture(variant=number) for number in (10, 11, 12)]

        def submit(qid):
            endpoint = f"/api/collections/bank/{bank['id']}/questions/{qid}"
            request(endpoint, token=foreground_cookie)
            _, _, elapsed = request(endpoint + '/actions', {'requestId': str(uuid.uuid4()), 'revision': 0,
                'action': 'submit', 'data': {'answer': answers[qid]}}, token=foreground_cookie)
            return elapsed

        for qid in identifiers[:20]:
            submit(qid)
        with ThreadPoolExecutor(max_workers=2) as foreground_workers:
            before_baseline = metrics()
            baseline = list(foreground_workers.map(submit, identifiers[20:120]))
            baseline_lru = metrics() - before_baseline
            # 每次对照都清空本项目的校验缓存，重复运行也必须是真正的冷导入。
            sql('DELETE FROM content_validation_cache;')
            running = threading.Event()

            def background_imports():
                packs = []
                for content in queued:
                    accepted, _, _ = request('/api/library/uploads?filename=background.qbank', content, 'application/octet-stream')
                    packs.append(accepted['packId'])
                deadline = time.monotonic()+180
                while time.monotonic() < deadline:
                    status = sql(f"SELECT status FROM content_import_job WHERE pack_id='{packs[0]}';")
                    if status == 'running':
                        running.set()
                        return packs
                    time.sleep(.1)
                raise RuntimeError('后台任务未开始')

            with ThreadPoolExecutor(max_workers=1) as importer:
                task = importer.submit(background_imports)
                if not running.wait(180):
                    task.result()
                    raise RuntimeError('未能与导入重叠')
                before_import = metrics()
                during = list(foreground_workers.map(submit, identifiers[120:220]))
                during_lru = metrics() - before_import
                packs = task.result()
        for pack in packs:
            deadline = time.monotonic()+180
            while sql(f"SELECT status FROM content_import_job WHERE pack_id='{pack}';") not in ('completed', 'failed'):
                if time.monotonic() > deadline:
                    raise RuntimeError('后台任务未结束')
                time.sleep(.1)
        def summary(values):
            return dict(count=len(values), p95Ms=sorted(values)[math.ceil(len(values)*.95)-1],
                        meanMs=sum(values)/len(values), maxMs=max(values), errors=0)
        result = dict(mode='foreground', vus=2, baseline=summary(baseline), duringImport=summary(during),
                      baselineLruStops=baseline_lru, duringImportLruStops=during_lru,
                      baselineSamplesMs=baseline, duringImportSamplesMs=during)
        (LOCAL / 'benchmark-foreground.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print(json.dumps(result))
        return
    results = []
    for repeat in range(args.repeats):
        if args.mode != 'cache':
            sql('DELETE FROM content_validation_cache;')
        before = metrics()
        accepted, _, upload = request('/api/library/uploads?filename=synthetic.qbank', data, 'application/octet-stream')
        pack = accepted['packId']
        deadline = time.monotonic()+180
        while True:
            status = sql(f"SELECT status FROM content_import_job WHERE pack_id='{pack}';")
            if status == 'completed':
                break
            if status == 'failed' or time.monotonic() > deadline:
                raise RuntimeError('后台任务未成功完成：' + status)
            # 仅测量脚本按状态轮询；单元与并发测试使用门控，不靠固定等待判断。
            time.sleep(.1)
        elapsed = float(sql(f"SELECT TIMESTAMPDIFF(MICROSECOND,started_at,updated_at)/1000 FROM content_import_job WHERE pack_id='{pack}';"))
        review = sql(f"SELECT review_status FROM catalog_pack_revision WHERE pack_id='{pack}';")
        if review != 'approved':
            raise RuntimeError('合成样例未通过：' + sql(f"SELECT IFNULL(validation_reason,'') FROM catalog_pack_revision WHERE pack_id='{pack}';"))
        results.append(dict(processingMs=elapsed, uploadMs=upload, lruStops=metrics()-before))
    result = dict(mode=args.mode, archiveBytes=len(data), banks=12, questions=1152, extensions=8, results=results)
    (LOCAL / f'benchmark-{args.mode}.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
