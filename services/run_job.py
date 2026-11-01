"""Run one full pipeline in a fresh directory without importing GPU libraries.

This is a local batch boundary, not a durable queue or an HTTP server.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import date, datetime, timezone
from services.keyword_policy import KeywordPolicy, DEFAULT_POLICY_PATH
from services.analysis_input import read_analysis_csv, write_analysis_csv
from services.dashboard_metrics import validate_source_rows

ROOT = Path(__file__).resolve().parents[1]


def _write_status(job_dir, status, **details):
    path = job_dir / "status.json"
    temporary = job_dir / "status.tmp"
    temporary.write_text(json.dumps({
        "status": status,
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        **details,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def _copy_input(source, target, period_start=None, period_end=None):
    start = date.fromisoformat(period_start) if period_start else None
    end = date.fromisoformat(period_end) if period_end else None
    if start and end and start > end:
        raise ValueError("Invalid analysis period")
    fields, rows = read_analysis_csv(source)
    validate_source_rows(rows)
    selected = [row for row in rows if
                (not start or datetime.fromisoformat(row['날짜'].strip()).date() >= start) and
                (not end or datetime.fromisoformat(row['날짜'].strip()).date() <= end)]
    if not selected:
        raise ValueError("No input rows in analysis period")
    write_analysis_csv(target, fields, selected)


def _run_with_progress(command, *, output, on_progress, **options):
    """Drain stdout while inference runs; no background sender survives the job."""
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding="utf-8", errors="replace", **options) as process:
        try:
            for line in process.stdout:
                output.write(line)
                output.flush()
                status = line.strip().removeprefix("TTOBAGI_PROGRESS:")
                if line.startswith("TTOBAGI_PROGRESS:") and status in {"PREPROCESSING", "ANALYZING"}:
                    try:
                        on_progress(status)
                    except Exception:
                        # Progress transport must never fail inference.
                        print(f"Progress callback failed: {status}", flush=True)
            return subprocess.CompletedProcess(command, process.wait())
        except BaseException:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise


def run_job(input_path, job_dir, *, templates=None, masking=True, translation=False,
            period_start=None, period_end=None, on_progress=None,
            min_cluster_size=5, min_samples=2):
    if type(min_cluster_size) is not int or min_cluster_size < 2 or type(min_samples) is not int or min_samples < 1:
        raise ValueError("min_cluster_size must be >= 2 and min_samples >= 1")
    input_path = Path(input_path).resolve(strict=True)
    templates = Path(templates or ROOT / "dataset/answer_templates.json").resolve(strict=True)
    if not input_path.is_file() or not templates.is_file():
        raise ValueError("Input and templates must be files")
    job_dir = Path(job_dir).resolve()
    # Exclusive creation prevents reusing a previous job or overwriting its output.
    job_dir.mkdir(parents=True, exist_ok=False)
    data_dir, log_dir = job_dir / "dataset", job_dir / "logs"
    try:
        data_dir.mkdir()
        log_dir.mkdir()
        # Validate the app resource afresh for every job, then pin its bytes.
        try:
            policy = KeywordPolicy.load(DEFAULT_POLICY_PATH)
        except ValueError as exc:
            print(str(exc), file=sys.stderr, flush=True)
            raise
        snapshot = job_dir / 'keyword_policy.json'
        snapshot.write_bytes(policy.source_bytes)
        _copy_input(input_path, data_dir / "tota24_unanswered_2026Q1.csv", period_start, period_end)
        shutil.copyfile(templates, data_dir / "answer_templates.json")
        env = dict(os.environ)
        env.update({
            "TTOBAGI_DATA_DIR": str(data_dir),
            "TTOBAGI_LOG_DIR": str(log_dir),
            "TTOBAGI_MASKING_ENABLED": str(masking).lower(),
            "TTOBAGI_TRANSLATION_ENABLED": str(translation).lower(),
            "PYTHONUNBUFFERED": "1",
            "TTOBAGI_MIN_CLUSTER_SIZE": str(min_cluster_size),
            "TTOBAGI_MIN_SAMPLES": str(min_samples),
            "TTOBAGI_KEYWORD_POLICY_PATH": str(snapshot),
        })
        # Shared FAQ sources stay outside job output; their explicit settings win.
        for key, filename in (
            ("TTOBAGI_GENERAL_FAQ_PATH", "일반상담.xlsx"),
            ("TTOBAGI_COUNSELLING_FAQ_PATH", "counselling_info.csv"),
        ):
            env[key] = str(Path(env.get(key) or ROOT / "dataset" / filename).resolve())
        source_kb = Path(env.get("TTOBAGI_DOCUMENT_KB_PATH") or ROOT / "dataset/seoulmetro_kb_clean.jsonl").resolve()
        if env.get("TTOBAGI_DOCUMENT_KB_PATH") and not source_kb.is_file():
            raise FileNotFoundError(source_kb)
        if source_kb.is_file():
            shutil.copyfile(source_kb, data_dir / "seoulmetro_kb_clean.jsonl")
        _write_status(job_dir, "RUNNING", masking=masking, translation=translation)
        with (log_dir / "stdout.log").open("w", encoding="utf-8") as output:
            execute = subprocess.run if on_progress is None else _run_with_progress
            transport = dict(stdout=output, stderr=subprocess.STDOUT, check=False) if on_progress is None else dict(output=output, on_progress=on_progress)
            result = execute(
                [sys.executable, "-u", "-m", "services.run_pipeline", "--full"],
                cwd=ROOT, env=env, **transport,
            )
        final = data_dir / "faq_candidates_final.json"
        if result.returncode:
            _write_status(job_dir, "FAILED", exitCode=result.returncode)
            return result.returncode
        # A successful subprocess must also leave a valid result artifact.
        payload = json.loads(final.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or not isinstance(payload.get("faqCandidates"), list):
            raise ValueError("Pipeline did not produce a faqCandidates array")
        _write_status(job_dir, "COMPLETED", result=str(final))
        return 0
    except Exception as exc:
        _write_status(job_dir, "FAILED", errorType=type(exc).__name__)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--job-dir", type=Path, required=True, help="Must not exist")
    parser.add_argument("--templates", type=Path)
    parser.add_argument("--masking", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--translation", action=argparse.BooleanOptionalAction, default=False)
    parser.add_argument("--period-start", help="Inclusive ISO date")
    parser.add_argument("--period-end", help="Inclusive ISO date")
    args = parser.parse_args()
    raise SystemExit(run_job(args.input, args.job_dir, templates=args.templates,
                            masking=args.masking, translation=args.translation,
                            period_start=args.period_start, period_end=args.period_end))


if __name__ == "__main__":
    main()
