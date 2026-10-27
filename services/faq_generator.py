"""또타24 FAQ 후보 생성 — Qwen2.5-7B-Instruct + 프롬프트 엔지니어링.

입력:  dataset/clusters.csv          (clustering.py 산출물)
       dataset/cluster_keywords.json (클러스터별 top 키워드)
       dataset/cluster_summary.csv   (클러스터별 size, isNoise)
출력:  dataset/faq_candidates.json   (백엔드 페이로드 키 네이밍, 백엔드 매핑 단계서 변환)

설계:
  - 클러스터별로 LLM 호출, JSON 출력 강제
  - LLM이 표준질문/답변/유사어 생성 + tag(FAQ/OUT_OF_SCOPE/EMERGENCY/REJECT) 판정
  - 노이즈(-1) 클러스터, 너무 작은 클러스터(min_size 미만)는 LLM 호출 안 함
  - 백엔드 페이로드 호환 키: clusterLabel, standardQuestion, answerDraft, synonyms[{text,type}]
"""
from __future__ import annotations

import json
import re
import time
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path, llm_memory_options
from services.synonym_validation import validate_synonyms

import pandas as pd
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM


def _nfd(p: Path) -> Path:
    return Path(unicodedata.normalize('NFD', str(p)))


# 0. 경로/모델/하이퍼파라미터
DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
CLUSTERS_CSV = DATA_DIR / 'clusters.csv'
KEYWORDS_JSON = DATA_DIR / 'cluster_keywords.json'
SUMMARY_CSV = DATA_DIR / 'cluster_summary.csv'
OUTPUT_JSON = DATA_DIR / 'faq_candidates.json'
OUTPUT_JSONL = DATA_DIR / 'faq_candidates.jsonl'  # per-cluster checkpoint

MODEL_NAME = 'Qwen/Qwen2.5-7B-Instruct'
DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'
DTYPE = torch.bfloat16

MAX_NEW_TOKENS = 800
TEMPERATURE = 0.3       # 사실성 우선
TOP_P = 0.9
MIN_CLUSTER_SIZE = 5    # 이보다 작은 클러스터는 LLM 호출 안 함
MAX_SAMPLES_PER_CLUSTER = 12  # 프롬프트에 넣을 대표 샘플 수


# 1. 프롬프트 빌더
PROMPT_TEMPLATE = """당신은 서울교통공사 챗봇 '또타24'의 FAQ 작성자입니다. 또타24는 지하철 이용에 관한 안내 챗봇입니다(노선/역/요금/환승/유실물/냉난방/시설 등).

아래는 사용자들이 보낸 미답변 질문들을 군집화한 결과입니다. 같은 군집의 질문들을 보고 표준 FAQ 1개를 작성하세요.

[군집 #{cluster_id}]  (총 {size}건)
대표 키워드: {keywords}

샘플 질문:
{samples}

다음 규칙에 따라 분류하고 출력하세요.

▶ tag 분류 기준:
  - "FAQ": 지하철 이용 관련 일반 문의. 표준질문/답변 작성 가능
  - "OUT_OF_SCOPE": 또타24 도메인 외 (예: 채용/조직도/재무/ESG/회사 정보)
  - "EMERGENCY": 응급/안전 신고 (화재/실신/폭행/성희롱 등) — 챗봇 답변보다 상담사 연결이 적절
  - "REJECT": 의미 없거나 너무 모호함 (숫자만, 단어 한두 개, 번역 실패 등)

▶ FAQ 작성 시:
  - standardQuestion: 자연스러운 한국어 한 문장 (구체적이고 답변 가능한 형태)
  - answerDraft: 운영자 가이드 톤. 사실 기반(hallucination 금지). 모르면 "○○로 문의" 식으로 안내
  - synonyms: 최대 10개. 개수를 채우려고 표현이나 오타를 만들지 마세요.
  - TYPO: 샘플에서 관측된 실제 철자/자모 오류만. 문장 생략, 물음표, 어미 변화는 오타가 아닙니다.
  - ABBR: 비번→비밀번호처럼 명확한 약어. SYNONYM: 정상 동의어/유사 질문/표기 변형.
  - '와이파이 비밀번호는?'는 SYNONYM, '와이파이 비번'은 ABBR, WiFi는 SYNONYM입니다.
  - 유사 표현은 표준질문과 같은 의도여야 합니다. '와이파이는?'처럼 의도가 모호한 표현은 피하세요.
  - 한글 등록 원칙: 모든 출력은 자연스러운 한국어로

▶ 출력은 반드시 아래 JSON 형식 하나만 (다른 텍스트 금지):

```json
{{
  "tag": "FAQ" | "OUT_OF_SCOPE" | "EMERGENCY" | "REJECT",
  "standardQuestion": "...",
  "answerDraft": "...",
  "synonyms": [
    {{"text": "...", "type": "TYPO|ABBR|SYNONYM"}}
  ],
  "reason": "(한 줄, 왜 이 tag로 분류했는지)"
}}
```

tag가 FAQ가 아니면 standardQuestion/answerDraft/synonyms는 빈 값으로:
  "standardQuestion": "", "answerDraft": "", "synonyms": []
"""


def build_prompt(cluster_id: int, size: int, keywords: list[str],
                 samples: list[str]) -> str:
    samples_str = '\n'.join(f'  - {s}' for s in samples[:MAX_SAMPLES_PER_CLUSTER])
    return PROMPT_TEMPLATE.format(
        cluster_id=cluster_id,
        size=size,
        keywords=', '.join(keywords) if keywords else '(없음)',
        samples=samples_str,
    )


# 2. LLM 호출
def load_model() -> tuple:
    print(f'모델 로드: {MODEL_NAME}')
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_NAME, torch_dtype=DTYPE, device_map='auto', trust_remote_code=True,
        **llm_memory_options(),
    ).eval()
    return tokenizer, model


@torch.no_grad()
def generate(prompt: str, tokenizer, model) -> str:
    messages = [
        {'role': 'system', 'content': '당신은 정확하고 사실 기반으로 답하는 한국어 FAQ 작성자입니다.'},
        {'role': 'user', 'content': prompt},
    ]
    inputs = tokenizer.apply_chat_template(
        messages, add_generation_prompt=True, return_tensors='pt', return_dict=True
    ).to(DEVICE)

    out = model.generate(
        **inputs,
        max_new_tokens=MAX_NEW_TOKENS,
        temperature=TEMPERATURE,
        top_p=TOP_P,
        do_sample=True,
        eos_token_id=tokenizer.eos_token_id,
        pad_token_id=tokenizer.eos_token_id,
    )
    response = tokenizer.decode(out[0][inputs['input_ids'].shape[1]:], skip_special_tokens=True)
    return response.strip()


# 3. JSON 파싱 (LLM 출력에서 첫 JSON 블록 추출)
RE_JSON_BLOCK = re.compile(r'```(?:json)?\s*(\{.*?\})\s*```', re.DOTALL)
RE_JSON_BARE = re.compile(r'(\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\})', re.DOTALL)


def parse_response(text: str) -> dict | None:
    m = RE_JSON_BLOCK.search(text) or RE_JSON_BARE.search(text)
    if not m:
        return None
    try:
        return json.loads(m.group(1))
    except json.JSONDecodeError:
        return None


def validate(obj: dict, sources=()) -> dict:
    """필수 키 보장 + 기본값 보강."""
    tag = obj.get('tag', 'REJECT')
    if tag not in {'FAQ', 'OUT_OF_SCOPE', 'EMERGENCY', 'REJECT'}:
        tag = 'REJECT'
    proposals = obj.get('synonyms') or []
    synonyms, _ = validate_synonyms(proposals, sources)
    return {
        'tag': tag,
        'standardQuestion': obj.get('standardQuestion', '') if tag == 'FAQ' else '',
        'answerDraft': obj.get('answerDraft', '') if tag == 'FAQ' else '',
        'synonyms': synonyms if tag == 'FAQ' else [],
        '_synonymProposals': proposals if tag == 'FAQ' else [],
        'reason': obj.get('reason', ''),
    }


# 4. 데이터 로드
def load_clusters() -> list[dict]:
    cdf = pd.read_csv(CLUSTERS_CSV, encoding='utf-8-sig')
    sdf = pd.read_csv(SUMMARY_CSV, encoding='utf-8-sig')
    with open(KEYWORDS_JSON, encoding='utf-8') as f:
        keywords = json.load(f)

    out = []
    for _, row in sdf.iterrows():
        cid = int(row['clusterId'])
        if row['isNoise']:
            continue
        if row['size'] < MIN_CLUSTER_SIZE:
            continue
        samples = cdf[cdf['clusterId'] == cid]['text'].astype(str).tolist()
        out.append({
            'clusterId': cid,
            'size': int(row['size']),
            'keywords': keywords.get(str(cid), []),
            'samples': samples,
            'sources': cdf[cdf['clusterId'] == cid][['logId', 'text']].to_dict('records'),
        })
    return out


# 5. 산출물 저장 — 백엔드 페이로드 키 네이밍
def save(results: list[dict]) -> None:
    payload = {
        'faqCandidates': [
            {
                'clusterLabel': r['clusterId'],
                'clusterSize': r['size'],
                'candidateType': 'NEW',           # EXPAND는 5단계 평가 이후 결정
                'tag': r['tag'],
                'standardQuestion': r['standardQuestion'],
                'answerDraft': r['answerDraft'],
                'synonyms': r['synonyms'],
                '_synonymProposals': r.get('_synonymProposals', r['synonyms']),
                'matchedFaqs': [],                # EXPAND 후보일 때만 채움 (현재 NEW only)
                'reason': r['reason'],
                'topKeywords': r['topKeywords'],
            }
            for r in results
        ]
    }
    with open(OUTPUT_JSON, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print(f'산출물: {OUTPUT_JSON.name} ({OUTPUT_JSON.stat().st_size:,} bytes, {len(results)}건)')


# 6. 메인
def main() -> None:
    if OUTPUT_JSONL.exists():
        OUTPUT_JSONL.unlink()
    clusters = load_clusters()
    print(f'대상 클러스터: {len(clusters)}개 (MIN_CLUSTER_SIZE={MIN_CLUSTER_SIZE} 이상, 노이즈 제외)\n')

    tokenizer, model = load_model()
    print(f'GPU: {DEVICE} / dtype: {DTYPE}\n')

    results = []
    for i, c in enumerate(clusters, 1):
        prompt = build_prompt(c['clusterId'], c['size'], c['keywords'], c['samples'])
        t0 = time.time()
        raw = generate(prompt, tokenizer, model)
        parsed = parse_response(raw)
        if parsed is None:
            print(f'  [C{c["clusterId"]:>2}] n={c["size"]:>3}  JSON 파싱 실패 → REJECT')
            obj = {'tag': 'REJECT', 'reason': 'JSON parse failed'}
        else:
            obj = parsed
        validated = validate(obj, c['sources'])
        validated['clusterId'] = c['clusterId']
        validated['size'] = c['size']
        validated['topKeywords'] = c['keywords'][:5]
        results.append(validated)
        with open(OUTPUT_JSONL, 'a', encoding='utf-8') as fp:
            fp.write(json.dumps(validated, ensure_ascii=False) + '\n')
        elapsed = time.time() - t0
        print(f'  [{i:>2}/{len(clusters)}] C{c["clusterId"]:>2}  '
              f'n={c["size"]:>3}  tag={validated["tag"]:<14}  '
              f'q="{validated["standardQuestion"][:40]}"  ({elapsed:.1f}s)')

    print()
    by_tag: dict = {}
    for r in results:
        by_tag[r['tag']] = by_tag.get(r['tag'], 0) + 1
    print('태그 분포:')
    for k, v in sorted(by_tag.items()):
        print(f'  {k}: {v}건')
    print()
    save(results)


if __name__ == '__main__':
    main()
