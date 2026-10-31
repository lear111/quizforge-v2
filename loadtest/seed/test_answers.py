import copy
import json
import unittest
import zipfile
from answers import TEXT, make_answer, sample_banks
from seed import ROOT


class SampleAnswersTest(unittest.TestCase):
    def schemas_and_data(self, name):
        with zipfile.ZipFile(ROOT / "samples/basic-types.qbank") as archive:
            prefix = f"extensions/基础题型/{name}/"
            manifest = json.loads(archive.read(prefix + "manifest.json"))
            question = json.loads(archive.read(f"question-banks/基础题型/{name}/bank.json"))["questions"][0]
            return (question["data"], json.loads(archive.read(prefix + manifest["questionSchema"])),
                    json.loads(archive.read(prefix + manifest["answerSchema"])))

    def test_choices_preserve_author_identifiers_instead_of_hardcoded_option_ids(self):
        for name, source, destination in [("单选题", "correctOptionId", "selectedOptionId"),
                                           ("多选题", "correctOptionIds", "selectedOptionIds")]:
            with self.subTest(name=name):
                data, question, answer = self.schemas_and_data(name)
                data = copy.deepcopy(data)
                ids = {option["id"]: f"custom-id_{i + 17}" for i, option in enumerate(data["options"])}
                for option in data["options"]: option["id"] = ids[option["id"]]
                data[source] = ids[data[source]] if name == "单选题" else [ids[value] for value in data[source]]
                self.assertEqual(make_answer(data, question, answer), {destination: data[source]})

    def test_false_judgement_is_not_replaced_by_truthy_default(self):
        data, question, answer = self.schemas_and_data("判断题")
        data["correctAnswer"] = False
        self.assertEqual(make_answer(data, question, answer), {"value": False})

    def test_short_answers_use_real_part_ids_and_schema_specific_text_representation(self):
        for name in ["简答题", "简答题（答题区）"]:
            with self.subTest(name=name):
                data, question, answer = self.schemas_and_data(name)
                data["parts"][0]["id"] = "custom-part"
                result = make_answer(data, question, answer)["responses"]
                self.assertEqual(set(result), {"custom-part"})
                response = result["custom-part"]
                self.assertIn(TEXT, json.dumps(response, ensure_ascii=False))
                if name == "简答题":
                    self.assertEqual(response["type"], "doc")
                else:
                    self.assertEqual(response["type"], answer["definitions"]["area"]["properties"]["type"]["const"])
                    self.assertEqual(response["width"], answer["definitions"]["area"]["properties"]["width"]["const"])
                    self.assertEqual(response["id"], "response-custom-part")
                    self.assertEqual(response["height"], 480)
                    self.assertEqual(response["strokes"], [])

    def test_answer_area_honors_the_authors_height(self):
        data, question, answer = self.schemas_and_data("简答题（答题区）")
        data["parts"][0]["answerHeight"] = 960
        self.assertEqual(make_answer(data, question, answer)["responses"][data["parts"][0]["id"]]["height"], 960)

    def test_unreferenced_questions_have_no_seed_answer(self):
        banks = sample_banks(ROOT / "samples/basic-types.qbank")
        self.assertEqual(len(banks), 5)
        for bank in banks.values():
            self.assertEqual(len(bank["answers"]), 1)
            self.assertNotIn("unreferenced-example", bank["answers"])

    def test_loadtest_package_has_300_visible_mixed_questions_and_central_outline(self):
        path = ROOT / "samples/loadtest-300.qbank"
        with zipfile.ZipFile(path) as archive:
            marker = json.loads(archive.read("quizforge-package.json"))
            self.assertEqual((marker["formatVersion"], marker["scope"]), (4, "bank"))
            bank = json.loads(archive.read("question-banks/基础题型/loadtest-300/bank.json"))
            ids = [q["id"] for q in bank["questions"]]
            self.assertEqual(len(set(ids)), 300)
            self.assertEqual([item["question"] for group in bank["outline"] for item in group["items"]], ids)
            self.assertEqual(len(bank["outline"]), 6)
            self.assertTrue(all("outline" not in q for q in bank["questions"]))
        answers = sample_banks(path)["loadtest-300"]["answers"]
        self.assertEqual(set(answers), set(ids))
        for field in ("selectedOptionId", "selectedOptionIds", "value"):
            self.assertEqual(sum(field in answer for answer in answers.values()), 100)
        self.assertEqual(sum(answer.get("value") is False for answer in answers.values()), 50)

    def test_unknown_schema_or_invalid_correct_option_fails_instead_of_guessing(self):
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            make_answer({}, {"properties": {}}, {"properties": {}})
        data, question, answer = self.schemas_and_data("单选题")
        data["correctOptionId"] = "missing"
        with self.assertRaisesRegex(ValueError, "missing"):
            make_answer(data, question, answer)


if __name__ == "__main__": unittest.main()
