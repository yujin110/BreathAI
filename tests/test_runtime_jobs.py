"""No GPU, network or production DB is used by these boundary tests."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

from services.run_job import run_job
from services.runtime_config import runtime_bool, runtime_path

INPUT_CSV = '번호,질문,날짜\n1,문의,2026-10-17\n'


class RuntimeJobsTest(unittest.TestCase):
    def test_two_jobs_have_separate_inputs_outputs_and_preserve_faq_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, templates = root / "input.csv", root / "templates.json"
            source.write_text(INPUT_CSV, encoding="utf-8")
            templates.write_text("{}", encoding="utf-8")
            environments = []

            def inference(command, **kwargs):
                env = kwargs["env"]
                environments.append(env)
                data = Path(env["TTOBAGI_DATA_DIR"])
                self.assertEqual((data / "tota24_unanswered_2026Q1.csv").read_text(encoding='utf-8-sig'), INPUT_CSV)
                (data / "faq_candidates_final.json").write_text('{"faqCandidates": []}')
                return types.SimpleNamespace(returncode=0)

            with patch("services.run_job.subprocess.run", side_effect=inference), patch.dict(
                os.environ, {"TTOBAGI_GENERAL_FAQ_PATH": str(root / "shared.xlsx")}
            ):
                for name in ("작업1", "작업2"):
                    job = root / name
                    self.assertEqual(run_job(source, job, templates=templates, min_cluster_size=12, min_samples=4), 0)
                    self.assertEqual(json.loads((job / "status.json").read_text(encoding="utf-8"))["status"], "COMPLETED")
            self.assertEqual(environments[0]["TTOBAGI_MIN_CLUSTER_SIZE"], "12")
            self.assertEqual(environments[0]["TTOBAGI_MIN_SAMPLES"], "4")
            self.assertNotEqual(environments[0]["TTOBAGI_DATA_DIR"], environments[1]["TTOBAGI_DATA_DIR"])
            self.assertEqual(environments[0]["TTOBAGI_GENERAL_FAQ_PATH"], str(root / "shared.xlsx"))
            self.assertEqual(environments[0]["TTOBAGI_MASKING_ENABLED"], "true")
            self.assertEqual(environments[0]["TTOBAGI_TRANSLATION_ENABLED"], "false")

    def test_existing_job_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.csv"
            source.write_text("original")
            with patch("services.run_job.subprocess.run") as process:
                with self.assertRaises(FileExistsError):
                    run_job(source, root, templates=source)
                process.assert_not_called()
            self.assertEqual(source.read_text(), "original")

    def test_failure_exit_and_missing_result_never_report_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.csv"
            source.write_text(INPUT_CSV, encoding='utf-8')
            with patch("services.run_job.subprocess.run", return_value=types.SimpleNamespace(returncode=7)):
                self.assertEqual(run_job(source, root / "failed", templates=source), 7)
            self.assertEqual(json.loads((root / "failed/status.json").read_text())["status"], "FAILED")
            with patch("services.run_job.subprocess.run", return_value=types.SimpleNamespace(returncode=0)):
                with self.assertRaises(FileNotFoundError):
                    run_job(source, root / "missing", templates=source)
            self.assertEqual(json.loads((root / "missing/status.json").read_text())["status"], "FAILED")

    def test_explicit_runtime_settings_and_legacy_defaults(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertTrue(runtime_bool("OPTION", True))
            self.assertEqual(runtime_path("LOCATION", Path("legacy")), Path("legacy"))
        with patch.dict(os.environ, {"OPTION": "false", "LOCATION": "작업"}):
            self.assertFalse(runtime_bool("OPTION", True))
            self.assertEqual(runtime_path("LOCATION", Path("legacy")), Path("작업").resolve())
        with patch.dict(os.environ, {"OPTION": "invalid"}):
            with self.assertRaises(ValueError):
                runtime_bool("OPTION", True)


class PreprocessOptionsTest(unittest.TestCase):
    @staticmethod
    def load_preprocessor():
        # Only translation is mocked; the real normalization and masking run.
        translator = types.ModuleType("deep_translator")
        translator.GoogleTranslator = object
        exceptions = types.ModuleType("deep_translator.exceptions")
        exceptions.TranslationNotFound = type("TranslationNotFound", (Exception,), {})
        exceptions.NotValidPayload = type("NotValidPayload", (Exception,), {})
        path = Path(__file__).resolve().parents[1] / "services/preprocess.py"
        spec = importlib.util.spec_from_file_location("tested_preprocess", path)
        module = importlib.util.module_from_spec(spec)
        with patch.dict("sys.modules", {"deep_translator": translator, "deep_translator.exceptions": exceptions}):
            spec.loader.exec_module(module)
        return module

    def test_options_control_real_preprocessing_and_keep_legacy_defaults(self):
        module = self.load_preprocessor()
        data = module.pd.DataFrame([{"번호": 1, "질문": "hello a@example.com", "날짜": "2026-10-17"}])
        with patch.object(module, "translate_to_ko", return_value="문의 a@example.com") as translate:
            output = module.run(data, masking_enabled=False, translation_enabled=False)
            translate.assert_not_called()
            self.assertIn("a@example.com", output.iloc[0]["normalizedForGeneration"])
            output = module.run(data)
            translate.assert_called_once()
            self.assertIn("[EMAIL]", output.iloc[0]["normalizedForGeneration"])
            self.assertNotIn("a@example.com", output.iloc[0]["normalizedForGeneration"])


if __name__ == "__main__":
    unittest.main()
