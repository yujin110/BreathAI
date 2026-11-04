"""Fresh, isolated chat checks; writes no FAQ/DB state.

Run the SAME JSON fixture in separate fresh output directories for each model.
This measures inference and preserves outputs for human/independent judging;
it does not claim to measure FAQ approval or complete pipeline performance.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--model')
    parser.add_argument('--max-new-tokens', type=int, default=600)
    parser.add_argument('--seed', type=int, default=42)
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    if not isinstance(cases, list) or not cases:
        raise ValueError('cases must be a nonempty list of {id, messages}')
    ids = set()
    for case in cases:
        if case['id'] in ids or not case['messages']:
            raise ValueError('case IDs must be unique and messages nonempty')
        ids.add(case['id'])
    if args.model:
        os.environ['TTOBAGI_LLM_MODEL'] = args.model
    args.run_dir.mkdir(parents=True, exist_ok=False)
    import torch
    import psutil
    from services.llm_runtime import model_id, load_chat_model, generate_chat

    def write(name, obj):
        (args.run_dir / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')

    write('cases.json', cases)
    process = psutil.Process()
    peak_rss = [process.memory_info().rss]
    gpu_samples = []
    stopped = threading.Event()
    def sample():
        while not stopped.wait(0.1):
            peak_rss[0] = max(peak_rss[0], process.memory_info().rss)
    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    def sample_gpu():
        while not stopped.is_set():
            try:
                sample = subprocess.check_output(
                    ['nvidia-smi', '--query-gpu=index,memory.used,utilization.gpu',
                     '--format=csv,noheader,nounits'], text=True, timeout=5)
                gpu_samples.append(dict(seconds=time.perf_counter(), csv=sample.strip()))
            except (OSError, subprocess.SubprocessError):
                return
            stopped.wait(1)
    gpu_sampler = threading.Thread(target=sample_gpu, daemon=True)
    gpu_sampler.start()
    versions = {p: importlib.metadata.version(p) for p in ['torch', 'transformers', 'accelerate', 'psutil']}
    manifest = dict(model=model_id(), revision=os.getenv('TTOBAGI_LLM_REVISION'),
                    quantization=os.getenv('TTOBAGI_LLM_QUANTIZATION', 'none'),
                    cpu_offload=os.getenv('TTOBAGI_LLM_CPU_OFFLOAD', 'false'),
                    gpu_memory_budget=os.getenv('TTOBAGI_LLM_GPU_MEMORY'),
                    cpu_memory_budget=os.getenv('TTOBAGI_LLM_CPU_MEMORY'),
                    context_tokens=os.getenv('TTOBAGI_LLM_CONTEXT_TOKENS', '8192'),
                    seed=args.seed, temperature=0, max_new_tokens=args.max_new_tokens,
                    cases_sha256=hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                    python=platform.python_version(), packages=versions,
                    human_approval_rate=None, quality_evaluation='pending')
    local_model = Path(model_id())
    manifest['local_model_bytes'] = (sum(p.stat().st_size for p in local_model.iterdir()
                                        if p.is_file() and not p.name.endswith(('.partial', '.tmp')))
                                     if local_model.is_dir() else None)
    try:
        manifest['nvidia_smi'] = subprocess.check_output(['nvidia-smi'], text=True)
    except (OSError, subprocess.CalledProcessError):
        manifest['nvidia_smi'] = None
    write('manifest.json', manifest)
    rows = []
    started = time.perf_counter()
    try:
        tok, model = load_chat_model(model_id())
        manifest['chat_template_sha256'] = hashlib.sha256(tok.chat_template.encode()).hexdigest()
        manifest['load_seconds'] = time.perf_counter() - started
        manifest['resolved_revision'] = getattr(model.config, '_commit_hash', None)
        manifest['dtype'] = str(model.dtype)
        manifest['device_map'] = {k: str(v) for k, v in getattr(model, 'hf_device_map', {}).items()}
        for case in cases:
            torch.manual_seed(args.seed)
            if torch.cuda.is_available():
                torch.cuda.synchronize()
                torch.cuda.reset_peak_memory_stats()
            start = time.perf_counter()
            row = dict(id=case['id'], status='failed')
            row['prompt_sha256'] = hashlib.sha256(
                json.dumps(case['messages'], ensure_ascii=False, sort_keys=True).encode()).hexdigest()
            try:
                answer = generate_chat(case['messages'], tok, model,
                                       max_new_tokens=args.max_new_tokens, temperature=0, top_p=1)
                if torch.cuda.is_available():
                    torch.cuda.synchronize()
                row.update(status='ok', answer=answer,
                           output_tokens=len(tok.encode(answer, add_special_tokens=False)))
            except Exception as exc:
                row['error'] = f'{type(exc).__name__}: {exc}'
            row['seconds'] = time.perf_counter() - start
            if torch.cuda.is_available():
                row['cuda_peak_allocated_bytes'] = torch.cuda.max_memory_allocated()
                row['cuda_peak_reserved_bytes'] = torch.cuda.max_memory_reserved()
            rows.append(row)
            write('answers.json', rows)
            print(case['id'], row['status'], round(row['seconds'], 2), flush=True)
    except Exception as exc:
        manifest['fatal_error'] = f'{type(exc).__name__}: {exc}'
        raise
    finally:
        stopped.set()
        sampler.join()
        gpu_sampler.join(timeout=6)
        write('gpu_samples.json', gpu_samples)
        elapsed = time.perf_counter() - started
        manifest.update(total_seconds=elapsed, process_peak_rss_sampled_bytes=peak_rss[0],
                        success_count=sum(r['status'] == 'ok' for r in rows),
                        case_count=len(cases), completed_case_count=len(rows),
                        requests_per_second_including_load=len(rows) / elapsed)
        write('manifest.json', manifest)
    if any(row['status'] != 'ok' for row in rows):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
