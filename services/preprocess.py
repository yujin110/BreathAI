"""또타24 미답변 로그 전처리 — 텍스트 정제 단일 책임.

기능:
  1. 언어 감지 및 PII 마스킹 (전화/이메일/주민/카드/URL → 토큰)
  2. 외국어→한국어 번역 (한글 등록 원칙)
  3. 열차번호 보호 토큰화 (4자리=서울메트로, 6자리=코레일)
  4. 이중 정규화 (클러스터링용 강함 / 생성용 오타 보존)

응답 라우팅·strategy 분류는 이 모듈 책임이 아님 — 클러스터링 + LLM이 담당.
라벨(`질문 유형`)은 평가(5단계) 단계에서 ground truth로 사용.
"""
from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path
from services.runtime_config import runtime_path

import pandas as pd
from services.translation import translate_with_azure


def _nfd(p: Path) -> Path:
    """프로젝트 폴더명이 NFD(macOS-style)로 저장된 경우를 위한 정규화."""
    return Path(unicodedata.normalize('NFD', str(p)))


# 0. 경로/컬럼 설정
DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
INPUT_CSV = DATA_DIR / 'tota24_unanswered_2026Q1.csv'
OUTPUT_CSV = DATA_DIR / 'preprocessed_full.csv'

COL_ID = '번호'
COL_QUESTION = '질문'
COL_VALID = '질의 유효/무효(유효:1, 무효:0)'
COL_TYPE = '질문 유형'
COL_KEYWORDS = '질문 핵심 키워드'
COL_ANSWER_DIR = '답변 생성 방향 검토'
COL_PROB = '답변확률'
COL_DATE = '날짜'


# 1. 입력 정규화: int/float/NaN/공백 섞인 값을 깔끔한 str로
def to_text(q) -> str:
    if pd.isna(q):
        return ''
    if isinstance(q, (int, float)):
        return str(int(q)) if float(q).is_integer() else str(q)
    return str(q).strip()


# 2. 언어 감지 (한·러·일·중·영 순)
def detect_lang(text: str) -> str:
    if not text:
        return 'empty'
    if re.search(r'[가-힣]', text):
        return 'ko'
    if re.search(r'[а-яА-Я]', text):
        return 'ru'
    if re.search(r'[ぁ-んァ-ン]', text):
        return 'ja'
    if re.search(r'[一-龯]', text):
        return 'zh'
    if re.search(r'[a-zA-Z]', text):
        return 'en'
    return 'unknown'


def translate_to_ko(text: str, src_lang: str) -> str | None:
    """한국어 등록 원칙: 외국어로 감지되면 한국어로 번역."""
    if src_lang in ('ko', 'empty', 'unknown') or not text.strip():
        return None
    return translate_with_azure(text)


# 3. PII 마스킹
RE_MOBILE = re.compile(r'(?<!\d)01[016789][-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)')
RE_TEL = re.compile(r'(?<!\d)0\d{1,2}[-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)')
RE_EMAIL = re.compile(r'[\w.+-]+@[\w-]+\.[\w.-]+')
RE_RRN = re.compile(r'(?<!\d)\d{6}-?[1-4]\d{6}(?!\d)')
RE_CARD = re.compile(r'(?<!\d)\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}(?!\d)')
RE_URL = re.compile(r'https?://\S+|www\.\S+')


def mask_pii(text: str) -> tuple[str, dict]:
    ent: dict[str, list[str]] = {}
    if m := RE_MOBILE.findall(text):
        ent['mobile'] = m
    text = RE_MOBILE.sub('[TEL]', text)
    if m := RE_TEL.findall(text):
        ent['tel'] = m
    text = RE_TEL.sub('[TEL]', text)
    if m := RE_EMAIL.findall(text):
        ent['email'] = m
    text = RE_EMAIL.sub('[EMAIL]', text)
    text = RE_RRN.sub('[RRN]', text)
    text = RE_CARD.sub('[CARD]', text)
    text = RE_URL.sub('[URL]', text)
    return text, ent


# 4. 열차번호 토큰화 (4자리=서울메트로, 6자리=코레일, 5/7+=폐기)
RE_NUM = re.compile(r'(?<!\d)\d+(?!\d)')


def tag_numbers(text: str) -> tuple[str, dict]:
    ent: dict[str, list[str]] = {}

    def repl(m: re.Match) -> str:
        n = m.group()
        L = len(n)
        if L == 4:
            ent.setdefault('metro_train', []).append(n)
            return '[METRO_TRAIN]'
        if L == 6:
            ent.setdefault('korail_train', []).append(n)
            return '[KORAIL_TRAIN]'
        if L == 5 or L >= 7:
            ent.setdefault('discarded_num', []).append(n)
            return ' '
        return n

    return RE_NUM.sub(repl, text), ent


# 5. 이중 정규화
RE_REPEAT_KOR = re.compile(r'([ㅋㅎㅠㅜㅡ])\1{2,}')
RE_REPEAT_CHAR = re.compile(r'(.)\1{3,}')
RE_EMOJI = re.compile(r'[\U0001F300-\U0001F9FF\U0001FA00-\U0001FAFF☀-➿]+')
RE_HANGUL_JAMO = re.compile(r'[ᄀ-ᇿ㄰-㆏]')
RE_WS = re.compile(r'\s+')


def normalize_for_clustering(text: str) -> str:
    """클러스터링용: 자모/이모지/반복 제거. 의미 단위 유지."""
    text = unicodedata.normalize('NFC', text)
    text = RE_EMOJI.sub(' ', text)
    text = RE_REPEAT_KOR.sub(r'\1\1', text)
    text = RE_REPEAT_CHAR.sub(r'\1\1', text)
    text = RE_HANGUL_JAMO.sub(' ', text)
    return RE_WS.sub(' ', text).strip()


def normalize_for_generation(text: str) -> str:
    """생성용: 오타 보존 (유사어 생성용)."""
    text = RE_EMOJI.sub(' ', text)
    text = RE_REPEAT_CHAR.sub(r'\1\1', text)
    return RE_WS.sub(' ', text).strip()


# 6. 파이프라인
def load() -> pd.DataFrame:
    target = DATA_DIR / 'analysis_targets.csv'
    df = pd.read_csv(target if target.exists() else INPUT_CSV, encoding='utf-8-sig', keep_default_na=False)
    df.columns = [c.replace('\n', '') for c in df.columns]
    return df


def run(df: pd.DataFrame, *, masking_enabled: bool = True,
        translation_enabled: bool = True) -> pd.DataFrame:
    """원문 → PII 마스킹 → 번역 → 열차번호 토큰화 → 이중 정규화."""
    rows = []
    translations: dict[str, str | None] = {}
    from services.analysis_progress import report
    report('PREPROCESSING', 0, len(df), 'LOG')
    for index, (_, row) in enumerate(df.iterrows(), 1):
        raw = to_text(row[COL_QUESTION])
        lang = detect_lang(raw)

        masked, pii_ent = mask_pii(raw) if masking_enabled else (raw, {})
        translated = None
        if translation_enabled and lang not in ('ko', 'empty', 'unknown'):
            if masked not in translations:
                translations[masked] = translate_to_ko(masked, lang)
            translated = translations[masked]
        if translated:
            masked, output_pii = mask_pii(translated) if masking_enabled else (translated, {})
            for kind, values in output_pii.items():
                pii_ent[kind] = list(dict.fromkeys(pii_ent.get(kind, []) + values))
        tagged, num_ent = tag_numbers(masked)

        clu = normalize_for_clustering(tagged)
        gen = normalize_for_generation(tagged)

        entities = {**pii_ent, **num_ent}
        if translated:
            entities['translated_from'] = lang

        rows.append({
            'logId': row[COL_ID],
            'date': row[COL_DATE],
            'originalText': raw,
            'lang': lang,
            'normalizedForClustering': clu,
            'normalizedForGeneration': gen,
            'extractedEntities': json.dumps(entities, ensure_ascii=False) if entities else '',
            'labelValid': row.get(COL_VALID),
            'labelType': row.get(COL_TYPE),
            'labelKeywords': row.get(COL_KEYWORDS),
            'labelAnswerDirection': row.get(COL_ANSWER_DIR),
            'labelMatchProbability': row.get(COL_PROB),
            'logType': row.get('logType', 'UNANSWER'),
        })
        if index % 32 == 0 or index == len(df):
            report('PREPROCESSING', index, len(df), 'LOG')
    from services.analysis_selection import PREPARED_COLUMNS
    return pd.DataFrame(rows, columns=PREPARED_COLUMNS)


def save(out: pd.DataFrame) -> None:
    out.to_csv(OUTPUT_CSV, index=False, encoding='utf-8-sig')
    print(f'  {OUTPUT_CSV.stat().st_size:>9,} bytes  {len(out):>5} rows  {OUTPUT_CSV.name}')


def report(out: pd.DataFrame) -> None:
    print('언어 분포:')
    print(out['lang'].value_counts().to_string())
    print()
    translated = out['extractedEntities'].astype(str).str.contains('translated_from').sum()
    print(f'번역된 행: {translated}건')
    print(f'PII 토큰 [TEL] 포함: {out["normalizedForClustering"].str.contains("[TEL]", regex=False, na=False).sum()}건')
    print(f'열차번호 [METRO_TRAIN]: {out["normalizedForClustering"].str.contains("[METRO_TRAIN]", regex=False, na=False).sum()}건')
    print(f'열차번호 [KORAIL_TRAIN]: {out["normalizedForClustering"].str.contains("[KORAIL_TRAIN]", regex=False, na=False).sum()}건')


def main() -> None:
    df = load()
    print(f'입력: {INPUT_CSV.name} ({len(df)}행)\n')
    from services.runtime_config import runtime_bool
    out = run(df,
              masking_enabled=runtime_bool('TTOBAGI_MASKING_ENABLED', True),
              translation_enabled=runtime_bool('TTOBAGI_TRANSLATION_ENABLED', True))
    print('산출물:')
    save(out)
    print()
    report(out)


if __name__ == '__main__':
    main()
