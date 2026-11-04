"""Controlled failure-oriented checks; not a representative performance study.

Runs real RAG/Refine prompt builders and the production generation settings.
Expected behavior is frozen before inference. No self-Judge or approval-rate score.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--cases', type=Path, default=Path(__file__).with_name('llama_grounding_cases.json'))
    args = parser.parse_args()
    cases = json.loads(args.cases.read_text(encoding='utf-8'))
    args.run_dir.mkdir(parents=True, exist_ok=False)
    def write(name, data):
        (args.run_dir / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    write('cases.json', cases)
    import torch
    from services import answer_regenerator as AR, answer_refiner as RF
    from services.llm_runtime import model_id
    root = Path(__file__).resolve().parents[1]
    templates = json.loads((root / 'dataset/answer_templates.json').read_text(encoding='utf-8'))
    AR.templates_meta = templates['_meta']
    manifest = dict(model=model_id(), revision=os.getenv('TTOBAGI_LLM_REVISION'),
                    quantization=os.getenv('TTOBAGI_LLM_QUANTIZATION'), seed_per_case=42,
                    max_new_tokens=AR.MAX_NEW_TOKENS, temperature=AR.TEMPERATURE,
                    top_p=AR.TOP_P, attempts_per_case=1,
                    source_sha256={p: hashlib.sha256((root / p).read_bytes()).hexdigest()
                                   for p in ['services/llm_runtime.py', 'services/answer_regenerator.py',
                                             'services/answer_refiner.py', 'services/grounded_answer.py', 'dataset/answer_templates.json']},
                    formal_performance_evaluation=False, approval_rate=None)
    write('manifest.json', manifest)
    tok, model = AR.load_model()
    manifest.update(dtype=str(model.dtype), device_map={k: str(v) for k, v in model.hf_device_map.items()})
    write('manifest.json', manifest)
    original_chat = AR.generate_chat
    trace = []
    def recorded_chat(messages, tokenizer, mdl, **settings):
        raw = original_chat(messages, tokenizer, mdl, **settings)
        trace.append(dict(messages=messages, settings=settings, raw=raw))
        return raw
    AR.generate_chat = recorded_chat
    rows = []
    for case in cases:
        torch.manual_seed(42)
        cluster = dict(tag='FAQ', clusterLabel=1, standardQuestion=case['question'], synonyms=[], topKeywords=[])
        docs = [dict(chunk_id=case.get('kb_id', f'synthetic:{i}'), text=t, rerank_score=.9,
                     question=case['question'] if case.get('source_type') == 'frozen_existing_kb' else '')
                for i, t in enumerate(case['evidence'])]
        cfg = dict(templates['FAQ'])
        if 'approved_facts' in case:
            cfg['approved_facts'] = case['approved_facts']
        if 'previous_answer' in case:
            prompt = RF.build_refine_prompt(cluster, cfg, templates['_shared'], docs,
                                            case['previous_answer'], {}, case['feedback'])
        else:
            prompt = AR.build_prompt(cluster, cfg, templates['_shared'], docs)
        write(case['id'] + '-input.json', dict(case=case, prompt=prompt))
        print('START', case['id'], flush=True)
        started = time.perf_counter()
        row = dict(id=case['id'], expected=case['expected'], assessment='pending_manual_review')
        try:
            row.update(status='generated', answer=AR.generate(prompt, tok, model))
            row['format_validation'] = AR.validate_answer(row['answer'], 'FAQ')
        except Exception as exc:
            row.update(status='failed', error=f'{type(exc).__name__}: {exc}')
        row['seconds'] = time.perf_counter() - started
        rows.append(row)
        write('answers.json', rows)
        write('generation_audit.json', trace)
        print('DONE', case['id'], row['status'], flush=True)
    write('summary.json', dict(request_count=len(rows), generated_count=sum(r['status'] == 'generated' for r in rows),
                              formal_performance_evaluation=False, human_approval_rate=None))
    if any(r['status'] != 'generated' for r in rows):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
