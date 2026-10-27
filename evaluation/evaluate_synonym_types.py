"""Paired real-cluster FAQ-generation pilot; no DB, callback or deployment writes."""
import ast
import csv
import hashlib
import json
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(WORKSPACE / 'tmp/synonym-eval-deps'))
os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
from services.synonym_validation import validate_synonyms, digest, load_policy


def prompt_constant(source):
    tree = ast.parse(source)
    return next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == 'PROMPT_TEMPLATE' for t in node.targets))


def parse_generated(raw):
    # Match the production parser: malformed outer JSON must not silently become
    # a valid inner synonym object.
    match = (re.search(r'```(?:json)?\s*(\{.*?\})\s*```', raw, re.DOTALL)
             or re.search(r'(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\})', raw, re.DOTALL))
    if not match:
        return None
    try:
        return json.loads(match.group(1))
    except json.JSONDecodeError:
        return None


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--model-size', choices=['7B', '3B'], default='7B')
    parser.add_argument('--baseline-ref', default='origin/develop')
    args = parser.parse_args()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    data = ROOT / 'dataset'
    rows = list(csv.DictReader((data / 'clusters.csv').open(encoding='utf-8-sig')))
    summary = list(csv.DictReader((data / 'cluster_summary.csv').open(encoding='utf-8-sig')))
    keywords = json.loads((data / 'cluster_keywords.json').read_text(encoding='utf-8'))
    eligible = sorted(int(r['clusterId']) for r in summary
                      if int(r['size']) >= 5 and r['isNoise'].lower() == 'false')
    # Freeze selection before seeing any model response. Largest two + seeded random ten.
    largest = sorted((r for r in summary if int(r['clusterId']) in eligible),
                     key=lambda r: (-int(r['size']), int(r['clusterId'])))[:2]
    selected = [int(r['clusterId']) for r in largest]
    selected += random.Random(42).sample([c for c in eligible if c not in selected], 10)
    baseline_sha = subprocess.check_output(['git', 'rev-parse', args.baseline_ref], cwd=ROOT).decode().strip()
    baseline_source = subprocess.check_output(['git', 'show', baseline_sha + ':services/faq_generator.py'],
                                             cwd=ROOT).decode('utf-8')
    templates = dict(baseline=prompt_constant(baseline_source),
                     revised=prompt_constant((ROOT / 'services/faq_generator.py').read_text(encoding='utf-8')))
    cases = []
    for cid in selected:
        cluster_rows = [r for r in rows if int(r['clusterId']) == cid]
        cases.append(dict(clusterLabel=cid, size=len(cluster_rows),
                          keywords=keywords.get(str(cid), []), sources=cluster_rows))
    (out / 'cases.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding='utf-8')
    model_id = f'Qwen/Qwen2.5-{args.model_size}-Instruct'
    model_path = WORKSPACE / f'BreathAI/tmp/models/Qwen--Qwen2.5-{args.model_size}-Instruct'
    manifest = dict(status='RUNNING', scope='12-cluster paired generation pilot, not full pipeline',
                    assessor='Codex review, not independent human ground truth',
                    model=model_id, modelPath=str(model_path),
                    baselineCommit=baseline_sha,
                    precision='bitsandbytes NF4 4-bit, float16 compute (differs from production bfloat16)',
                    eligibleClusters=len(eligible), sourceRows=len(rows), selected=selected,
                    selection='largest 2 + random.Random(42) sample of remaining 10, before inference',
                    temperature=.3, top_p=.9, max_new_tokens=800, seeds='42 + clusterLabel',
                    inputSha256={name: hashlib.sha256((data / name).read_bytes()).hexdigest()
                                 for name in ['clusters.csv', 'cluster_summary.csv', 'cluster_keywords.json']},
                    promptSha256={k: hashlib.sha256(v.encode()).hexdigest() for k, v in templates.items()},
                    policySha256=digest(load_policy()), policy=load_policy())
    def save():
        (out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    if (out / 'generations.jsonl').exists():
        previous = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
        for key in ['model', 'precision', 'selected', 'inputSha256', 'promptSha256',
                    'policySha256', 'temperature', 'top_p', 'max_new_tokens', 'seeds']:
            if previous.get(key) != manifest[key]:
                raise ValueError('Cannot resume with changed evaluation condition: ' + key)
    save()
    import torch
    import transformers
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig, set_seed
    from transformers import StoppingCriteria, StoppingCriteriaList
    class Progress(StoppingCriteria):
        def __init__(self, prompt_length):
            self.prompt_length = prompt_length
        def __call__(self, input_ids, scores, **kwargs):
            count = input_ids.shape[1] - self.prompt_length
            if count % 50 == 0:
                print(f'GENERATING tokens={count}', flush=True)
            return False
    manifest.update(torch=torch.__version__, transformers=transformers.__version__,
                    gpu=torch.cuda.get_device_name(0))
    save()
    done = set()
    result_path = out / 'generations.jsonl'
    if result_path.exists():
        for line in result_path.read_text(encoding='utf-8').splitlines():
            r = json.loads(line)
            done.add((r['clusterLabel'], r['variant']))
    started = time.monotonic()
    try:
        tokenizer = AutoTokenizer.from_pretrained(model_path, local_files_only=True)
        model = AutoModelForCausalLM.from_pretrained(model_path, local_files_only=True,
            quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type='nf4',
                bnb_4bit_compute_dtype=torch.float16, bnb_4bit_use_double_quant=True),
            device_map={'': 0}, torch_dtype=torch.float16, low_cpu_mem_usage=True).eval()
        manifest['loadSeconds'] = time.monotonic() - started
        save()
        for case in cases:
            for variant, template in templates.items():
                cid = case['clusterLabel']
                if (cid, variant) in done:
                    continue
                prompt = template.format(cluster_id=cid, size=case['size'],
                    keywords=', '.join(case['keywords']) or '(없음)',
                    samples='\n'.join('  - ' + r['text'] for r in case['sources'][:12]))
                messages = [dict(role='system', content='당신은 정확하고 사실 기반으로 답하는 한국어 FAQ 작성자입니다.'),
                            dict(role='user', content=prompt)]
                inputs = tokenizer.apply_chat_template(messages, add_generation_prompt=True,
                    return_tensors='pt', return_dict=True).to('cuda')
                set_seed(42 + cid)
                tick = time.monotonic()
                with torch.inference_mode():
                    output = model.generate(**inputs, do_sample=True, temperature=.3, top_p=.9,
                        max_new_tokens=800, eos_token_id=tokenizer.eos_token_id,
                        stopping_criteria=StoppingCriteriaList([Progress(inputs['input_ids'].shape[1])]),
                        pad_token_id=tokenizer.eos_token_id)
                tokens = output[0][inputs['input_ids'].shape[1]:]
                raw = tokenizer.decode(tokens, skip_special_tokens=True).strip()
                parsed = parse_generated(raw)
                synonyms = parsed.get('synonyms', []) if isinstance(parsed, dict) else []
                accepted, audit = validate_synonyms(synonyms, case['sources'])
                record = dict(clusterLabel=cid, variant=variant, raw=raw, parsed=parsed,
                              tokens=len(tokens), truncated=len(tokens) == 800,
                              seconds=time.monotonic() - tick, accepted=accepted, audit=audit)
                with result_path.open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(record, ensure_ascii=False) + '\n')
                print(json.dumps({k: record[k] for k in ['clusterLabel', 'variant', 'tokens', 'seconds', 'truncated']}), flush=True)
        manifest.update(status='COMPLETED', elapsedSeconds=time.monotonic() - started,
                        peakVramBytes=torch.cuda.max_memory_allocated())
    except Exception as exc:
        manifest.update(status='FAILED', errorType=type(exc).__name__, error=str(exc))
        raise
    finally:
        save()


if __name__ == '__main__':
    main()
