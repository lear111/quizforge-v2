"""Answer adapters for the pinned basic extensions; read source packages, never private HTTP data."""
import copy
import json
import zipfile


TEXT = "压测作答：缓存减少重复计算，提高响应速度，同时应注意数据一致性。"


def resolve(schema, root):
    """Only local schema references are needed by the pinned sample extensions."""
    while "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/"):
            raise ValueError("Sample schema must use local references")
        schema = root
        for key in ref[2:].split("/"):
            schema = schema[key.replace("~1", "/").replace("~0", "~")]
    return schema


def make_answer(data, question_schema, answer_schema):
    """Recognize schema fields, not extension names; preserve author option/part identifiers."""
    question = question_schema["properties"]
    answer = answer_schema["properties"]
    option_ids = {option["id"] for option in data.get("options", [])}
    if "correctOptionId" in question and "selectedOptionId" in answer:
        correct = data["correctOptionId"]
        if correct not in option_ids:
            raise ValueError("Correct option is missing")
        return {"selectedOptionId": correct}
    if "correctOptionIds" in question and "selectedOptionIds" in answer:
        correct = data["correctOptionIds"]
        if not set(correct) <= option_ids:
            raise ValueError("Correct options are missing")
        return {"selectedOptionIds": copy.deepcopy(correct)}
    if "correctAnswer" in question and "value" in answer:
        return {"value": data["correctAnswer"]}
    if "parts" in question and "responses" in answer:
        response_schema = resolve(answer["responses"]["additionalProperties"], answer_schema)
        fields = response_schema.get("properties", {})
        responses = {}
        for part in data["parts"]:
            if fields.get("type", {}).get("const") == "qf-answer-area":
                # Constants and dimensions come from the answer schema, not a made-up wire format.
                area = {key: value["const"] for key, value in fields.items() if "const" in value}
                # Pinned 答题区/rules.js: areaId(), height(), areaValid(). These cross-field
                # constraints are stronger than JSON Schema (response- prefix, default height 480).
                area.update(id="response-" + part["id"], height=part.get("answerHeight", 480),
                            strokes=[], text=TEXT)
                responses[part["id"]] = area
            elif "allOf" in response_schema:  # pinned richtext document schema
                responses[part["id"]] = {"type": "doc", "content": [
                    {"type": "paragraph", "content": [{"type": "text", "text": TEXT}]}]}
            else:
                raise ValueError("Unsupported sample response schema")
        return {"responses": responses}
    raise ValueError("Unsupported sample question/answer schemas")


def sample_banks(path):
    """Visible questions only, in central-outline order; keys match imported collection titles."""
    with zipfile.ZipFile(path) as archive:
        def read(name):
            return json.loads(archive.read(name))
        banks = {}
        for name in sorted(archive.namelist()):
            if not name.startswith("question-banks/") or not name.endswith("/bank.json"):
                continue
            bank = read(name)
            questions = {q["id"]: q for q in bank["questions"]}
            visible = [questions[item["question"]] for group in bank["outline"] for item in group["items"]]
            answers, scores, kinds = {}, {}, {}
            for question in visible:
                extension = question.get("extension", bank.get("extension"))["path"]
                manifest = read(extension + "/manifest.json")
                q_schema = read(extension + "/" + manifest["questionSchema"])
                a_schema = read(extension + "/" + manifest["answerSchema"])
                answers[question["id"]] = make_answer(question["data"], q_schema, a_schema)
                objective = any(key in q_schema["properties"] for key in ("correctOptionId", "correctOptionIds", "correctAnswer"))
                if objective:
                    scores[question["id"]] = question["data"]["maxScore"]
                kinds[question["id"]] = "objective" if objective else (
                    "area" if "area" in a_schema.get("definitions", {}) else "text")
            if bank["title"] in banks:
                raise ValueError("Duplicate sample bank title")
            banks[bank["title"]] = {"answers": answers, "scores": scores, "kinds": kinds}
        return banks
