"""Real-model execution checks with isolated artifacts and a read-only test KB.

This is a functional smoke check, not a representative quality benchmark.
"""
import argparse
from collections import Counter
import csv
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import time


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def record_runtime(root):
    from importlib.metadata import version
    import platform
    project = Path(__file__).resolve().parents[1]
    names = ['preprocess', 'clustering', 'question_identity', 'faq_generator', 'faq_matching',
             'faq_kb', 'retriever', 'llm_runtime', 'run_pipeline', 'callback_payload']
    write(root / 'runtime_config.json', dict(
        python=platform.python_version(),
        packages={name: version(name) for name in ('torch', 'transformers', 'bitsandbytes', 'numpy',
                  'umap-learn', 'hdbscan', 'sentence-transformers', 'fastapi')},
        configuration={key: os.getenv(key) for key in (
            'TTOBAGI_LLM_MODEL', 'TTOBAGI_LLM_DEVICE_MAP', 'TTOBAGI_LLM_QUANTIZATION',
            'TTOBAGI_LLM_CPU_OFFLOAD', 'TTOBAGI_EMBEDDING_MODEL', 'TTOBAGI_RETRIEVAL_MODEL',
            'TTOBAGI_RERANK_MODEL', 'TTOBAGI_CE_DEVICE', 'TTOBAGI_RETRIEVAL_BATCH_SIZE')},
        sourceSha256={name: hashlib.sha256((project / 'services' / (name + '.py')).read_bytes()).hexdigest()
                      for name in names}, formalQualityEvaluation=False))


def prepare(source, root):
    import openpyxl
    from services.log_classification import classify_percent
    root.mkdir(parents=True, exist_ok=False)
    book = openpyxl.load_workbook(source, read_only=True, data_only=True)
    iterator = book.active.iter_rows(values_only=True)
    headers = list(next(iterator))
    buckets = {'lost': [], 'cold': [], 'correct': []}
    seen = set()
    counts = Counter()
    for source_row, values in enumerate(iterator, 2):
        row = dict(zip(headers, values))
        question = str(row.get('질문') or '').strip()
        if not question:
            continue
        try:
            label = classify_percent(str(row['답변확률']))
        except (ValueError, KeyError):
            counts['invalidScore'] += 1
            continue
        counts[label] += 1
        bucket = ('correct' if label == 'CORRECT' else
                  'lost' if re.search('분실|유실|가방|잃어|놓고', question) else
                  'cold' if re.search('춥|추워|냉방|에어컨', question) else None)
        if not bucket or len(buckets[bucket]) >= (2 if bucket == 'correct' else 8) or question in seen:
            continue
        date = row['날짜']
        if isinstance(date, datetime):
            date = date.isoformat()
        else:
            date = datetime.fromisoformat(str(date).strip()).isoformat()
        seen.add(question)
        buckets[bucket].append(dict(질문=question, 날짜=date, keywordMatchPercent=str(row['답변확률']),
                                    sourceRow=source_row, sourceId=row.get('번호'), topic=bucket))
    selected = buckets['lost'] + buckets['cold'] + buckets['correct']
    if len(buckets['lost']) < 5 or len(buckets['cold']) < 5:
        raise ValueError(f'Not enough real questions: { {k: len(v) for k,v in buckets.items()} }')
    # Deliberate repeat rows verify duplicate identity; local IDs stay unique.
    selected += [dict(buckets['lost'][0], augmentedDuplicate=True) for _ in range(3)]
    with (root / 'input.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=['번호', '질문', '날짜', 'keywordMatchPercent'])
        writer.writeheader()
        for i, row in enumerate(selected, 1):
            writer.writerow({'번호': i, **{key: row[key] for key in writer.fieldnames[1:]}})
    write(root / 'fixture_manifest.json', dict(
        source=str(source), sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        sourceClassificationCounts=dict(counts), inputRows=len(selected),
        selection=dict(topicCounts={k: len(v) for k,v in buckets.items()}, augmentedDuplicateRows=3),
        mapping=[dict(localLogId=i, **{k: v for k,v in row.items() if k != '질문'})
                 for i,row in enumerate(selected, 1)], formalQualityEvaluation=False))
    print(json.dumps(dict(inputRows=len(selected), topics={k: len(v) for k,v in buckets.items()}, sourceCounts=dict(counts))))


def seed_kb():
    import pymysql
    from services.faq_kb import load_handcrafted_faqs
    records, _ = load_handcrafted_faqs()
    selected = []
    for pattern in ('분실|유실', '춥|추워|냉방|에어컨'):
        selected.extend([r for r in records if re.search(pattern, r['question'])][:3])
    conn = pymysql.connect(host='127.0.0.1', port=33317, user='root', password='validation-only', database='validation', charset='utf8mb4')
    with conn.cursor() as cursor:
        cursor.execute('CREATE TABLE IF NOT EXISTS gold_faq (faq_id BIGINT PRIMARY KEY, source_seq_num INT, q_type INT, category VARCHAR(100), question TEXT, answer TEXT, keywords JSON, is_active BOOLEAN)')
        for i, row in enumerate(selected, 900001):
            cursor.execute('INSERT INTO gold_faq VALUES (%s,%s,%s,%s,%s,%s,%s,1) ON DUPLICATE KEY UPDATE question=VALUES(question), answer=VALUES(answer)',
                           (i,row.get('source_seq_num'),row.get('q_type'),row.get('category'),row['question'],row['answer'],json.dumps(row.get('keywords',[]),ensure_ascii=False)))
        cursor.execute("CREATE USER IF NOT EXISTS 'validation_reader'@'%' IDENTIFIED BY 'validation-readonly'")
        cursor.execute("GRANT SELECT ON validation.* TO 'validation_reader'@'%'")
    conn.commit()
    conn.close()
    print(json.dumps(dict(seededOperatingFaqs=len(selected), testDatabaseOnly=True)))


def intent_check(root):
    from services.question_identity import IntentVerifier
    verifier = IntentVerifier()
    cases = [
        ('same_cooling_direction', ['지하철이 너무 추워요'], ['냉방을 줄여주세요'], 'same'),
        ('opposite_cooling_direction', ['냉방을 줄여주세요'], ['에어컨을 더 세게 틀어주세요'], 'different'),
        ('same_lost_property', ['열차에 가방을 두고 내렸어요'], ['지하철에서 잃어버린 가방을 찾고 싶어요'], 'same'),
    ]
    results = []
    for name, left, right, expected in cases:
        started = time.monotonic()
        decision = verifier(left, right)
        if verifier.model is not None:
            import torch
            import transformers
            write(root / 'runtime.json', dict(
                model=os.getenv('TTOBAGI_LLM_MODEL'), torchVersion=torch.__version__,
                transformersVersion=transformers.__version__, gpu=torch.cuda.get_device_name(0),
                dtype=str(verifier.model.dtype), quantization=os.getenv('TTOBAGI_LLM_QUANTIZATION'),
                deviceMap={k: str(v) for k,v in verifier.model.hf_device_map.items()},
                gpuPeakAllocatedBytes=torch.cuda.max_memory_allocated()))
        results.append(dict(case=name, expected=expected, **decision, seconds=time.monotonic()-started))
        write(root / 'intent_results.json', results)
        print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    if any(r['decision'] != r['expected'] for r in results):
        raise SystemExit('Real intent cases did not all pass')


def run(root):
    from services.run_job import run_job
    started = time.monotonic()
    code = run_job(root / 'input.csv', root / 'job', analysis_mode='IMPROVEMENT', translation=False)
    if code:
        raise SystemExit(f'Pipeline exit {code}; inspect {root}/job/logs/stdout.log')
    verify(root, root / 'job', started)


def verify(root, job, started, extra=None):
    from services.callback_payload import build_callback
    callback = build_callback(job)
    write(root / 'callback.json', callback)
    data = job / 'dataset'
    with (data / 'clusters.csv').open(encoding='utf-8-sig', newline='') as stream:
        rows = list(csv.DictReader(stream))
    labels = {}
    for row in rows:
        labels.setdefault(row['text'], set()).add(int(row['clusterId']))
    candidate_count = len(callback['faqCandidates'])
    final = json.loads((data / 'faq_candidates_final.json').read_text(encoding='utf-8'))['faqCandidates']
    groups = json.loads((data / 'question_groups.json').read_text(encoding='utf-8'))
    faq_audit = json.loads((data / 'faq_merge_history.json').read_text(encoding='utf-8'))
    audits = groups['mergeHistory'] + faq_audit['decisions'] + [
        item for c in final for item in c.get('matchEvidence', [])]
    errors = [item for item in audits if re.match(r'\w*(?:Error|Exception):',
              item.get('reason', item.get('matchDecisionReason', '')))]
    write(root / 'new_stage_errors.json', errors)
    retrieval = json.loads((data / 'faq_retrieval.json').read_text(encoding='utf-8'))
    checkpoints = [json.loads(line) for line in (data / 'faq_candidates.jsonl').read_text(encoding='utf-8').splitlines()]
    checks = dict(callbackCompleted=callback['status'] == 'COMPLETED',
                  allIdenticalQuestionsTogether=all(len(v) == 1 for v in labels.values()),
                  originalLogsCovered=len(rows) == callback['analysisScope']['eligibleLogCount'],
                  faqAndAnswerPathExecuted=any(c['tag'] == 'FAQ' for c in final),
                  retrievalExecuted=bool(retrieval['faqRetrieval']),
                  newStagesWithoutRuntimeErrors=not errors,
                  faqSamplesUnique=all(len(c['samples']) == len(set(c['samples'])) for c in checkpoints),
                  everyCandidateHasAnswer=all(c['answerDraft'].strip() for c in final if c['tag'] == 'FAQ'))
    write(root / 'summary.json', dict(checks=checks, seconds=time.monotonic()-started,
          analysisScope=callback['analysisScope'], candidateCount=candidate_count,
          candidateTypes=dict(Counter(c['candidateType'] for c in final)),
          model=os.getenv('TTOBAGI_LLM_MODEL'), apiChecks=extra, jobDir=str(job), formalQualityEvaluation=False))
    print(json.dumps(dict(checks=checks, candidateCount=candidate_count), ensure_ascii=False), flush=True)
    if not all(checks.values()):
        raise SystemExit('Runtime checks incomplete or failed')


def api_run(root):
    """Real HTTP upload -> durable queue -> POSIX child -> real full pipeline."""
    import subprocess
    import sys
    import httpx
    from services.job_store import JobStore
    root = root.resolve()
    jobs = root / 'api-jobs'
    if jobs.exists():
        raise ValueError('Use a fresh API jobs directory')
    env = dict(os.environ, TTOBAGI_JOB_ROOT=str(jobs), TTOBAGI_AI_API_TOKEN='runtime-validation-only',
               TTOBAGI_STANDALONE='true')
    base = 'http://127.0.0.1:33318'
    started = time.monotonic()
    worker = None
    with (root / 'api.log').open('w') as api_log, (root / 'worker.log').open('w') as worker_log:
        server = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'services.pipeline_api:create_app',
                                   '--factory', '--host', '127.0.0.1', '--port', '33318'],
                                  env=env, stdout=api_log, stderr=subprocess.STDOUT)
        try:
            with httpx.Client(base_url=base, timeout=15) as client:
                for _ in range(60):
                    try:
                        if client.get('/health').status_code == 200:
                            break
                    except httpx.RequestError:
                        pass
                    if server.poll() is not None:
                        raise RuntimeError('API exited during startup')
                    time.sleep(1)
                else:
                    raise TimeoutError('API startup deadline exceeded')
                unauthorized = client.get('/jobs/1').status_code == 401
                client.headers['Authorization'] = 'Bearer runtime-validation-only'
                def submit():
                    with (root / 'input.csv').open('rb') as stream:
                        return client.post('/pipeline', files={'file': ('input.csv', stream, 'text/csv')},
                            data=dict(uploadId='runtime-check', analysisId='1', analysisMode='IMPROVEMENT',
                                      isTranslationEnabled='false'))
                response = submit()
                response.raise_for_status()
                accepted = response.status_code == 202
                write(root / 'api_submit.json', response.json())
                worker = subprocess.Popen([sys.executable, '-u', '-m', 'services.pipeline_worker'],
                                          env=env, stdout=worker_log, stderr=subprocess.STDOUT)
                previous = None
                while time.monotonic() - started < 7200:
                    response = client.get('/jobs/1')
                    response.raise_for_status()
                    status = response.json()
                    write(root / 'api_status.json', status)
                    phase = (status['status'], (status.get('progress') or {}).get('phase'))
                    if phase != previous:
                        print(json.dumps(status, ensure_ascii=False), flush=True)
                        previous = phase
                    if status['status'] in {'COMPLETED', 'FAILED', 'CANCELED'}:
                        break
                    if worker.poll() is not None:
                        raise RuntimeError('Worker exited unexpectedly')
                    time.sleep(3)
                else:
                    raise TimeoutError('Pipeline exceeded two-hour verification deadline')
                duplicate = submit()
                duplicate.raise_for_status()
                write(root / 'api_duplicate.json', duplicate.json())
                checks = dict(authRejected=unauthorized, accepted202=accepted,
                              duplicateRecognized=duplicate.json()['duplicate'],
                              finalStatusCompleted=status['status'] == 'COMPLETED',
                              finalProgress=(status.get('progress') or {}).get('phase') == 'FINALIZING')
                write(root / 'api_checks.json', checks)
                payload = json.loads(JobStore(jobs).get(1)['payload'])
                write(root / 'stored_callback.json', payload)
                if not all(checks.values()):
                    raise RuntimeError('HTTP/worker execution check failed; inspect job logs')
                verify(root, jobs / 'analysis-1', started, checks)
        finally:
            for process in (worker, server):
                if process is not None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def positive_match(root):
    """Real retrieval, intent matching and grounded answer on an existing FAQ."""
    import gc
    import torch
    from services import retriever as ret, answer_regenerator as ar, run_pipeline as pipeline
    from services.faq_matching import decide_match
    from services.question_identity import IntentVerifier
    from services.grounded_answer import INSUFFICIENT, CONFLICT
    output = root / 'positive-match'
    output.mkdir(exist_ok=False)
    kb = ret.load_kb()
    target = next(item for item in kb if '객실 난방 및 냉방' in item['question'])
    query = target['question']
    enc = ret.SentenceTransformer(ret.DENSE_MODEL, device=ret.DEVICE)
    enc.max_seq_length = ret.MAX_SEQ_LENGTH
    embeddings = enc.encode([item['text'] for item in kb], batch_size=1,
                            normalize_embeddings=True, convert_to_numpy=True)
    dense = ret.search(query, enc, kb, embeddings)
    reranker = ret.CrossEncoder(ret.RERANK_MODEL, device=ret.DEVICE, max_length=512,
                               activation_fn=torch.nn.Sigmoid())
    ranked = ret.rerank(query, dense, reranker, top_k=len(dense))
    write(output / 'retrieval.json', dict(query=query, expectedFaqId=target['faq_id'], retrieved=ranked))
    del enc, reranker
    gc.collect()
    torch.cuda.empty_cache()
    verifier = IntentVerifier()
    decision = decide_match(query, ranked, verifier, ret.FAQ_MATCH_THRESHOLD)
    write(output / 'decision.json', decision)
    checks = dict(expandConfirmed=decision['candidateType'] == 'EXPAND',
                  correctOperatingId=bool(decision['matchedFaqs']) and
                  decision['matchedFaqs'][0]['matchedFaqId'] == target['faq_id'])
    write(output / 'checks.json', checks)
    if not all(checks.values()):
        raise SystemExit('Real operating FAQ match failed; inspect positive-match artifacts')
    best = next(item for item in ranked if item['faq_id'] == target['faq_id'])
    missing_id = decide_match(query, [{**best, 'faq_id': None}], verifier, ret.FAQ_MATCH_THRESHOLD)
    write(output / 'missing_id.json', missing_id)
    checks['missingIdNotExpanded'] = (missing_id['candidateType'] == 'NEW' and
        missing_id['matchEvidence'][0]['matchStatus'] == 'ID_LINK_ERROR')
    templates = json.loads((Path(__file__).resolve().parents[1] / 'dataset/answer_templates.json').read_text(encoding='utf-8'))
    ar.templates_meta = templates['_meta']
    record = dict(clusterLabel=101, query=query, retrieved=ranked[:5], **decision)
    candidate = dict(clusterLabel=101, tag='FAQ', standardQuestion=query, topKeywords=[],
                     synonyms=[], answerDraft='', **decision)
    answer, missing, attempts = pipeline.stage_regenerate(candidate, {101: record}, templates,
                                                        verifier.tokenizer, verifier.model)
    write(output / 'answer.json', dict(answer=answer, validationMissing=missing, attempts=attempts,
                                       usedEvidence=ar.select_kb_chunks(record)))
    checks['groundedAnswerNotAbstention'] = bool(answer.strip()) and not any(
        message in answer for message in (INSUFFICIENT, CONFLICT))
    checks['groundedAnswerValidationPassed'] = not missing
    write(output / 'checks.json', checks)
    print(json.dumps(checks), flush=True)
    if not all(checks.values()):
        raise SystemExit('Positive match/grounded-answer checks failed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'seed-kb', 'intent', 'run', 'api', 'positive-match'])
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    if args.phase == 'prepare':
        if args.source is None:
            parser.error('--source is required for prepare')
        prepare(args.source, args.root)
    elif args.phase == 'seed-kb':
        seed_kb()
    elif args.phase == 'intent':
        intent_check(args.root)
    elif args.phase == 'api':
        api_run(args.root)
    elif args.phase == 'positive-match':
        positive_match(args.root)
    else:
        run(args.root)


if __name__ == '__main__':
    main()
