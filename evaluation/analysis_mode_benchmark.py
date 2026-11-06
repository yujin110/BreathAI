"""Run real ALL/IMPROVEMENT jobs against prepared fixtures, without production writes.

Example: python -m evaluation.analysis_mode_benchmark --case 200=fixture200.csv
  --case 5000=fixture5000.csv --case 19000=fixture19000.csv --output evaluation/results/modes-001
Requires the full inference runtime/models and configured read-only KB sources.
Each repetition starts an isolated pipeline process; cache regime must be documented separately.
"""
import argparse
import hashlib
from pathlib import Path
import time

from services.analysis_selection import MODES, write_json
from services.callback_payload import build_callback
from services.run_job import run_job


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', action='append', required=True, help='name=CSV path; prepare representative fixtures separately')
    parser.add_argument('--output', type=Path, required=True, help='New directory only')
    parser.add_argument('--repetitions', type=int, default=1)
    parser.add_argument('--modes', nargs='+', choices=MODES, default=['ALL', 'IMPROVEMENT'])
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error('repetitions must be positive')
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for case_index, case in enumerate(args.case, 1):
        name, separator, filename = case.partition('=')
        if not separator or not name:
            parser.error('case requires name=path')
        path = Path(filename).resolve(strict=True)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        for mode in args.modes:
            for repetition in range(1, args.repetitions + 1):
                job = args.output / f'case-{case_index}-{mode}-{repetition}'
                started = time.monotonic()
                record = dict(case=name, inputSha256=digest, analysisMode=mode,
                              repetition=repetition, cacheRegime='NOT_CONTROLLED', jobDir=job.name)
                try:
                    code = run_job(path, job, analysis_mode=mode, translation=False)
                    if code:
                        raise RuntimeError(f'Pipeline exit {code}')
                    payload = build_callback(job)
                    write_json(job / 'callback.json', payload)
                    record.update(status='COMPLETED', analysisScope=payload['analysisScope'],
                                  completionReason=payload['completionReason'])
                except Exception as exc:
                    record.update(status='FAILED', errorType=type(exc).__name__)
                record['wallSeconds'] = round(time.monotonic() - started, 6)
                results.append(record)
                write_json(args.output / 'summary.json', results)
                print(f"case={case_index} mode={mode} run={repetition} status={record['status']} elapsed={record['wallSeconds']}s", flush=True)
    if any(record['status'] != 'COMPLETED' for record in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
