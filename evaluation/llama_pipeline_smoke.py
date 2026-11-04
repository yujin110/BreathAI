"""Exercise real production prompt builders and inference on synthetic evidence.

This is a functional check, not a representative quality or approval-rate study.
No database access, preprocessing, clustering or live retrieval is performed.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--stage', choices=['all', 'faq', 'answers'], default='all')
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    import torch
    from services import faq_generator as FG, answer_regenerator as AR
    from services import run_pipeline as RP, answer_evaluator as AE
    from services.llm_runtime import model_id
    from services.retrieval_contract import index_retrieval, bind_retrieval

    def write(name, obj):
        (args.run_dir / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')

    torch.manual_seed(42)
    templates = json.loads((Path(__file__).resolve().parents[1] / 'dataset/answer_templates.json').read_text(encoding='utf-8'))
    AR.templates_meta = templates['_meta']
    question = '열차에 가방을 두고 내렸는데 어떻게 문의하나요?'
    evidence = '열차에 물건을 두고 내렸다면 분실 시간, 이용 노선, 열차 진행 방향, 승하차 역을 확인하여 역 직원에게 문의한다.'
    retrieval = index_retrieval({'faqRetrieval': [dict(
        clusterLabel=1, query=question, candidateType='NEW',
        retrieved=[dict(chunk_id='synthetic:lost-property', text=evidence, rerank_score=.9)])]})
    candidate = bind_retrieval([dict(clusterLabel=1, tag='FAQ', standardQuestion=question,
                                   answerDraft='', synonyms=[], topKeywords=['가방', '분실'])], retrieval)[0]
    candidate['retrievalEvidence'] = dict(query=question, used=AR.select_kb_chunks(retrieval[1]))
    write('fixture.json', dict(candidate=candidate, retrieval=retrieval,
                              synthetic_evidence=True, templates=templates))
    if args.prepare_only:
        AR.build_prompt(candidate, templates['FAQ'], templates['_shared'],
                        AR.select_kb_chunks(retrieval[1]))
        print('Fixture and production RAG prompt preflight completed', flush=True)
        return
    tok, model = AR.load_model()
    write('runtime.json', dict(model=model_id(), dtype=str(model.dtype),
                               device_map={k: str(v) for k, v in model.hf_device_map.items()},
                               seed=42, generator_max_tokens=FG.MAX_NEW_TOKENS,
                               regeneration_max_tokens=AR.MAX_NEW_TOKENS,
                               temperature=AR.TEMPERATURE))
    results = []
    audit = []
    original_generate = AR.generate
    def recorded_generate(prompt, tokenizer, mdl):
        raw = original_generate(prompt, tokenizer, mdl)
        audit.append(dict(prompt=prompt, prompt_sha256=hashlib.sha256(prompt.encode()).hexdigest(), answer=raw))
        write('generation_audit.json', audit)
        return raw
    AR.generate = recorded_generate
    started = time.perf_counter()
    # Synthesis remains a separate JSON generator, using the loaded shared model.
    checks = {}
    if args.stage != 'answers':
        print('Starting FAQ synthesis', flush=True)
        prompt = FG.build_prompt(1, 5, ['가방', '분실'], [question] * 5)
        normalized, trials = FG.generate_candidate(prompt, tok, model)
        raw = trials[-1]
        parsed = FG.parse_response(raw)
        results.append(dict(stage='faq_synthesis', raw=raw, trials=trials, parsed=parsed,
                            normalized=normalized))
        write('results.json', results)
        checks.update(faq_json_parsed=isinstance(parsed, dict),
                      faq_fields_valid=bool(normalized['tag'] == 'FAQ'
                                            and normalized['standardQuestion'] and normalized['answerDraft']))
        if args.stage == 'faq':
            write('summary.json', dict(model=model_id(), stage='faq_synthesis',
                                       schema_check=all(checks.values()), attempts=len(trials),
                                       formal_performance_evaluation=False))
            if not all(checks.values()):
                raise SystemExit(1)
            print('FAQ schema functional check completed', flush=True)
            return
    print('Starting RAG regeneration', flush=True)
    body, missing, attempts = RP.stage_regenerate(candidate, retrieval, templates, tok, model)
    results.append(dict(stage='rag_regeneration', answer=body, missing=missing, attempts=attempts))
    write('results.json', results)
    trial = copy.deepcopy(candidate)
    # Deliberately wrong answer and explicit feedback exercise Refine; no fake Judge score.
    trial['answerDraft'] = '내일 오전 9시에서 오후 5시 사이에 방문하세요.'
    trial['evaluation'] = dict(llm_axes={}, review_reasons=[
        '근거에 없는 방문 시간을 삭제하고 분실 시간·노선·진행 방향·승하차 역을 확인해 역 직원에게 문의하도록 안내하세요.'])
    print('Starting Refine', flush=True)
    q, answer, changed, attempts = RP.stage_refine(trial, retrieval, templates, tok, model)
    results.append(dict(stage='refine', question=q, answer=answer,
                        question_changed=changed, attempts=attempts))
    write('results.json', results)
    print('Starting evidence-aware runtime Judge', flush=True)
    judge_input = {**candidate, 'answerDraft': body}
    judge, raw_judge = AE.llm_judge(tok, model, judge_input)
    results.append(dict(stage='runtime_judge', raw=raw_judge, parsed=judge,
                        independent_quality_evaluation=False))
    write('results.json', results)
    checks.update(regeneration_nonempty=bool(body.strip()),
                  refine_nonempty=bool(answer.strip()), runtime_judge_json_parsed=judge is not None)
    write('summary.json', dict(model=model_id(), seconds=time.perf_counter() - started,
                              stages=len(results), gpu_peak_bytes=torch.cuda.max_memory_allocated()
                              if torch.cuda.is_available() else None,
                              contract_checks=checks,
                              formal_performance_evaluation=False))
    print('Pipeline functional check completed', flush=True)
    if not all(checks.values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
