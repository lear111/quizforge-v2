"""Rebuild deterministic format-4 samples from quizforge_web@4522bb8; Python standard library only."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import zipfile

UPSTREAM = Path(sys.argv[1])
COMMIT = '4522bb81c2126a699fcf72ce61d074dbc1264161'
TYPES = ['单选题', '多选题', '判断题', '简答题', '简答题（答题区）']
GROUP = '基础题型'
OUTPUT = Path(__file__).resolve().parent

def git(*args):
    return subprocess.check_output(['git', '-C', str(UPSTREAM), *args])

def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')

def image(value):
    if isinstance(value, dict):
        return value.get('type') == 'image' or any(image(child) for child in value.values())
    return isinstance(value, list) and any(image(child) for child in value)

def files(types, scope):
    result = {'quizforge-package.json': encode(dict(format='quizforge-bank-group', formatVersion=4, group=GROUP, scope=scope))}
    for name in types:
        prefix = f'extensions/{GROUP}/{name}'
        paths = git('ls-tree', '-r', '-z', '--name-only', COMMIT, prefix).decode('utf-8').split('\0')
        content = {path[len(prefix)+1:]: git('show', f'{COMMIT}:{path}') for path in paths if path}
        example = json.loads(content['examples/bank.json'])
        example['formatVersion'] = 2
        example['extension'] = {'path': prefix}
        for question in example['questions']:
            if 'extension' in question:
                question['extension'] = {'path': prefix}
        content['examples/bank.json'] = encode(example)
        result.update({f'{prefix}/{path}': value for path, value in content.items()})
        chosen = next((q for q in example['questions'] if name == '单选题' and image(q['data'])), example['questions'][0])
        question = copy.deepcopy(chosen)
        question.pop('extension', None)
        bank = dict(formatVersion=2, title=name, extension={'path': prefix}, questions=[question])
        result[f'question-banks/{GROUP}/{name}/bank.json'] = encode(bank)
        for path, value in content.items():
            if path.startswith('examples/assets/'):
                result[f'question-banks/{GROUP}/{name}/assets/{path.split("/")[-1]}'] = value
    return result

for filename, types, scope in [('basic-types.qbank', TYPES, 'group'), ('single-choice.qbank', TYPES[:1], 'bank')]:
    with zipfile.ZipFile(OUTPUT / filename, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for path, data in sorted(files(types, scope).items()):
            entry = zipfile.ZipInfo(path, date_time=(2026, 10, 30, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    print(filename, (OUTPUT / filename).stat().st_size)
