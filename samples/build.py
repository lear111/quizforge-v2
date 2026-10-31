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
BANK_IDS = {
    '单选题': '60000000-0000-4000-8000-000000000001',
    '多选题': '60000000-0000-4000-8000-000000000002',
    '判断题': '60000000-0000-4000-8000-000000000003',
    '简答题': '60000000-0000-4000-8000-000000000004',
    '简答题（答题区）': '60000000-0000-4000-8000-000000000005',
}
EXAMPLE_IDS = {
    '单选题': '60000000-0000-4000-8000-000000000101',
    '多选题': '60000000-0000-4000-8000-000000000102',
    '判断题': '60000000-0000-4000-8000-000000000103',
    '简答题': '60000000-0000-4000-8000-000000000104',
    '简答题（答题区）': '60000000-0000-4000-8000-000000000105',
}

def git(*args):
    return subprocess.check_output(['git', '-C', str(UPSTREAM), *args])

def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')

def image(value):
    if isinstance(value, dict):
        return value.get('type') == 'image' or any(image(child) for child in value.values())
    return isinstance(value, list) and any(image(child) for child in value)

def centralize(bank):
    items = []
    for question in bank['questions']:
        item = {'question': question['id']}
        old = question.pop('outline', None)
        if old:
            item['label' if old['level'] == 'question' else 'parts'] = old.get('label', old.get('items'))
        items.append(item)
    bank['outline'] = [{'items': items}]

def files(types, scope):
    result = {'quizforge-package.json': encode(dict(format='quizforge-bank-group', formatVersion=4, group=GROUP, scope=scope))}
    for name in types:
        prefix = f'extensions/{GROUP}/{name}'
        paths = git('ls-tree', '-r', '-z', '--name-only', COMMIT, prefix).decode('utf-8').split('\0')
        content = {path[len(prefix)+1:]: git('show', f'{COMMIT}:{path}') for path in paths if path}
        example = json.loads(content['examples/bank.json'])
        example['formatVersion'] = 2
        example['id'] = EXAMPLE_IDS[name]
        example['extension'] = {'path': prefix}
        for question in example['questions']:
            if 'extension' in question:
                question['extension'] = {'path': prefix}
        centralize(example)
        content['examples/bank.json'] = encode(example)
        result.update({f'{prefix}/{path}': value for path, value in content.items()})
        chosen = next((q for q in example['questions'] if name == '单选题' and image(q['data'])), example['questions'][0])
        question = copy.deepcopy(chosen)
        question.pop('extension', None)
        bank = dict(formatVersion=2, id=BANK_IDS[name], title=name, extension={'path': prefix}, questions=[question])
        centralize(bank)
        bank['outline'][0]['group'] = '基础练习'
        if name == '单选题':
            bank['outline'][0]['items'][0]['label'] = '示例 A'
        if name == '简答题':
            bank['outline'][0]['items'][0]['parts'] = [{'id': question['data']['parts'][0]['id'], 'label': '简答'}]
        unused = copy.deepcopy(question)
        unused['id'] = 'unreferenced-example'
        bank['questions'].append(unused)
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

# A single bank exercises ordered groups, adjacent equal names and an unnamed group.
content = files(['简答题'], 'bank')
path = f'question-banks/{GROUP}/简答题/bank.json'
bank = json.loads(content[path])
first = bank['questions'][0]
second, third = copy.deepcopy(first), copy.deepcopy(first)
second['id'], third['id'] = 'second', 'third'
bank['questions'].extend([second, third])
bank['outline'] = [
    {'group': '作者分组', 'items': [{'question': 'second', 'label': '第二题先做'}]},
    {'group': '作者分组', 'items': [{'question': first['id'], 'parts': [{'id': first['data']['parts'][0]['id'], 'label': '小题'}]}]},
    {'items': [{'question': 'third'}]},
]
content[path] = encode(bank)
with zipfile.ZipFile(OUTPUT / 'central-outline.qbank', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for path, data in sorted(content.items()):
        entry = zipfile.ZipInfo(path, date_time=(2026, 10, 30, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, data)

# An independent mixed bank for repeatable load preparation. Keep the pinned extension bytes.
content = files(TYPES[:3], 'bank')
templates = [json.loads(content[f'question-banks/{GROUP}/{name}/bank.json'])['questions'][0] for name in TYPES[:3]]
content = {path: data for path, data in content.items() if not path.startswith('question-banks/')}
questions = []
for index in range(300):
    kind = index % 3
    question = copy.deepcopy(templates[kind])
    question['id'] = f'loadtest-{index + 1:03d}'
    question['title'] = f'压测第 {index + 1} 题'
    question['extension'] = {'path': f'extensions/{GROUP}/{TYPES[kind]}'}
    data = question['data']
    data['stem'] = {'type': 'doc', 'content': [{'type': 'paragraph', 'content': [
        {'type': 'text', 'text': f'压测第 {index + 1} 题（{TYPES[kind]}）'}]}]}
    if 'options' in data:
        identifiers = {option['id']: f'option-{index + 1}-{number}' for number, option in enumerate(data['options'])}
        for option in data['options']: option['id'] = identifiers[option['id']]
        if kind == 0: data['correctOptionId'] = identifiers[data['correctOptionId']]
        else: data['correctOptionIds'] = [identifiers[value] for value in data['correctOptionIds']]
    else:
        data['correctAnswer'] = (index // 3) % 2 == 0
    questions.append(question)
bank = dict(formatVersion=2, id='60000000-0000-4000-8000-000000000300', title='loadtest-300',
            questions=questions, outline=[{'group': f'压测分组 {offset // 50 + 1}',
                'items': [{'question': q['id']} for q in questions[offset:offset + 50]]} for offset in range(0, 300, 50)])
content[f'question-banks/{GROUP}/loadtest-300/bank.json'] = encode(bank)
with zipfile.ZipFile(OUTPUT / 'loadtest-300.qbank', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for path, data in sorted(content.items()):
        entry = zipfile.ZipInfo(path, date_time=(2026, 10, 30, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, data)
print('loadtest-300.qbank', (OUTPUT / 'loadtest-300.qbank').stat().st_size)
