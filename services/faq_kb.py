"""기존 FAQ와 운영 승인 FAQ를 RAG 검색용 KB로 통합한다.

이 프로젝트에서 "기존 FAQ"는 다음 두 서울교통공사 수제작 자료를 모두 뜻한다.

1. 일반상담 Excel: 카테고리, 질문, 답변, 유사질문 제공
2. counselling_info CSV: 기존 FAQ 식별자(seq_num), q_type, 질문, 답변 제공

두 자료에서 동일한 질문 또는 답변은 하나의 레코드로 연결해 양쪽 메타데이터를
합치고, 어느 한쪽에만 있는 FAQ도 검색 KB에 포함한다. 여기에 운영자가 승인한
`gold_faq`를 추가하여 다음 분석의 기존 FAQ 검색과 답변 생성 근거로 사용한다.
"""
from __future__ import annotations

import html
import json
import os
import re
from pathlib import Path
from typing import Any, Callable


## FAQ 지식 베이스 코드

ConnectionFactory = Callable[[], Any]
DATA_DIR = Path(__file__).resolve().parent.parent / "dataset"
DEFAULT_GENERAL_FAQ = DATA_DIR / "일반상담.xlsx"
DEFAULT_COUNSELLING_FAQ = DATA_DIR / "counselling_info.csv"


def _default_connection_factory():
    """운영자가 승인한 FAQ를 읽기 위한 MariaDB 연결을 생성한다.

    입력 환경변수: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD, DB_NAME
    출력: DictCursor를 사용하는 PyMySQL connection
    """
    if os.getenv("TTOBAGI_DB_ENV_FILE") or os.getenv("TTOBAGI_DB_URL"):
        from services.bronze_faq_ingest import connect_db
        return connect_db(os.getenv("TTOBAGI_DB_ENV_FILE"))

    import pymysql
    from pymysql.cursors import DictCursor

    return pymysql.connect(
        host=os.getenv("DB_HOST", "host.docker.internal"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USER", "tbg_admin"),
        password=os.getenv("DB_PASSWORD", "tbg_pw_260501"),
        database=os.getenv("DB_NAME", "ttobagi_db"),
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=True,
    )


def load_base_kb(path: Path) -> list[dict]:
    """선택적인 서울교통공사 문서 KB JSONL을 읽는다.

    일반상담/counselling FAQ와 별개인 안내 문서용 입력이다. 각 줄은 최소한
    임베딩할 `text`를 포함해야 하며 파일이 없으면 빈 목록을 반환한다.
    """
    if not path.exists():
        return []

    records: list[dict] = []
    with path.open(encoding="utf-8") as fp:
        for line_number, line in enumerate(fp, 1):
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            if not str(record.get("text", "")).strip():
                raise ValueError(f"KB {path.name}:{line_number}에 text가 없습니다.")
            records.append(record)
    return records


def _parse_keywords(value: Any) -> list[str]:
    """DB JSON 문자열 또는 구분자 문자열을 검색 키워드 목록으로 정규화한다."""
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return []
        try:
            parsed = json.loads(stripped)
        except json.JSONDecodeError:
            parsed = [part.strip() for part in re.split(r"[,|]", stripped)]
        if isinstance(parsed, list):
            return [str(item).strip() for item in parsed if str(item).strip()]
    return []


def _faq_to_kb_record(row: dict) -> dict:
    """gold_faq 한 행을 다른 FAQ 원천과 동일한 검색 레코드로 변환한다.

    검색 모델이 질문·답변·키워드를 함께 참고하도록 세 필드를 `text`로 합친다.
    """
    faq_id = int(row["faq_id"])
    question = _clean_text(row.get("question"))
    answer = _clean_text(row.get("answer"))
    keywords = _parse_keywords(row.get("keywords"))
    synonyms = _parse_keywords(row.get("synonyms"))
    keyword_text = ", ".join(keywords)

    parts = [f"질문: {question}", f"답변: {answer}"]
    if keyword_text:
        parts.append(f"키워드: {keyword_text}")
    if synonyms:
        parts.append(f"유사 표현: {', '.join(synonyms)}")

    return {
        "source_type": "approved_faq",
        "source_url": None,
        "menuIdx": None,
        "page_label": f"운영 FAQ #{faq_id}",
        "chunk_id": f"gold_faq:{faq_id}",
        "faq_id": faq_id,
        "source_seq_num": row.get("source_seq_num"),
        "q_type": row.get("q_type"),
        "category": row.get("category"),
        "question": question,
        "answer": answer,
        "keywords": keywords,
        "synonyms": synonyms,
        "text": "\n".join(parts),
    }


def load_approved_faqs(
    connection_factory: ConnectionFactory | None = None,
    *, include_source_copies: bool = False,
) -> list[dict]:
    """`gold_faq`에서 활성화된 운영 승인 FAQ를 읽는다.

    읽는 데이터: faq_id, source_seq_num, q_type, category, question, answer,
    keywords. 비활성 FAQ와 질문/답변이 비어 있는 행은 검색에서 제외한다.
    기존 FAQ를 복사한 행은 수정 이력이 있을 때만 읽는다. 수정하지 않은
    복사본은 원천 파일에서 이미 읽으므로 중복으로 추가하지 않는다.
    원천 FAQ가 없는 배포에서는 include_source_copies=True로 활성 복사본도 읽는다.
    """
    factory = connection_factory or _default_connection_factory
    conn = factory()
    try:
        with conn.cursor() as cursor:
            source_filter = "" if include_source_copies else """
                  AND (f.source_seq_num IS NULL
                       OR EXISTS (
                           SELECT 1 FROM gold_faq_edit_history h
                           WHERE h.faq_id = f.faq_id
                       ))
            """
            cursor.execute(
                f"""
                SELECT f.faq_id, f.source_seq_num, f.q_type, f.category,
                       f.question, f.answer, f.keywords, f.synonyms
                FROM gold_faq f
                WHERE f.is_active = 1
                  {source_filter}
                ORDER BY f.faq_id
                """
            )
            rows = cursor.fetchall()
    finally:
        conn.close()

    return [
        _faq_to_kb_record(row)
        for row in rows
        if str(row.get("question") or "").strip()
        and str(row.get("answer") or "").strip()
    ]


def _clean_text(value: Any) -> str:
    """HTML과 원천 구분자를 제거해 검색 가능한 문장으로 정리한다."""
    if value is None:
        return ""
    try:
        import pandas as pd
        if pd.isna(value):
            return ""
    except (ImportError, TypeError, ValueError):
        pass
    text = html.unescape(str(value))
    text = re.sub(r"<(script|style)\b[^>]*>.*?</\1\s*>", " ", text, flags=re.I | re.S)
    text = re.sub(r"<img\b[^>]*>", " ", text, flags=re.I | re.S)
    text = re.sub(r"data:image/[^;\s]+;base64,[A-Za-z0-9+/=]+", " ", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text).replace("||", " ")
    return re.sub(r"\s+", " ", text).strip()


def _match_key(value: Any) -> str:
    """두 수제작 데이터셋의 질문/답변 연결에 사용할 비교 키를 만든다."""
    return re.sub(r"[^0-9A-Za-z가-힣]", "", _clean_text(value)).casefold()


def _read_counselling(path: Path) -> list[dict]:
    """counselling_info CSV를 기존 FAQ 검색 레코드로 변환한다.

    주요 입력 컬럼:
    - seq_num: matchedFaqSeqNum으로 반환할 기존 FAQ 식별자
    - q_type: 카테고리 번호
    - ci_question / ci_answer0: 검색 질문과 답변
    - ci_disp_name / link1~5: 표시명과 참고 링크

    CSV 배포본별 인코딩 차이를 고려해 UTF-8과 CP949를 순서대로 시도한다.
    """
    import pandas as pd

    last_error = None
    for encoding in ("utf-8-sig", "cp949", "utf-8"):
        try:
            frame = pd.read_csv(path, encoding=encoding)
            break
        except UnicodeDecodeError as exc:
            last_error = exc
    else:
        raise ValueError(f"counselling_info 인코딩을 판별할 수 없습니다: {path}") from last_error

    required = {"seq_num", "q_type", "ci_question", "ci_answer0"}
    missing = required.difference(frame.columns)
    if missing:
        raise ValueError(f"counselling_info 필수 컬럼 누락: {', '.join(sorted(missing))}")

    records = []
    for row in frame.to_dict("records"):
        question = _clean_text(row.get("ci_question"))
        answer = _clean_text(row.get("ci_answer0"))
        if not question or not answer:
            continue
        seq_num = int(row["seq_num"])
        records.append({
            "source_type": "counselling_info",
            "source_seq_num": seq_num,
            "q_type": int(row["q_type"]) if row.get("q_type") is not None else None,
            "category": None,
            "question": question,
            "answer": answer,
            "keywords": [],
            "page_label": _clean_text(row.get("ci_disp_name")) or question,
            "chunk_id": f"counselling_info:{seq_num}",
            "source_url": next(
                (_clean_text(row.get(f"link{i}")) for i in range(1, 6)
                 if _clean_text(row.get(f"link{i}"))),
                None,
            ),
            "menuIdx": None,
            "text": f"질문: {question}\n답변: {answer}",
        })
    return records


def _read_general(path: Path, counselling: list[dict]) -> tuple[list[dict], set[int]]:
    """일반상담 Excel을 기존 FAQ 검색 레코드로 변환한다.

    일반상담과 counselling_info는 모두 기존 FAQ이다. 질문 또는 답변이 같은
    항목에는 counselling의 seq_num/q_type을 결합하고, 매칭되지 않은 일반상담
    항목도 category와 유사질문을 가진 독립 FAQ로 유지한다.

    전달 파일의 한글 헤더 인코딩이 일정하지 않아 고정 열 순서를 사용한다:
    0 번호, 2 카테고리, 3 상담제목, 4 되묻기, 5 답변, 6~15 유사질문1~10.
    """
    import pandas as pd

    frame = pd.read_excel(path)
    if frame.shape[1] < 16:
        raise ValueError(f"일반상담 파일 컬럼이 16개보다 적습니다: {path}")
    by_question = {_match_key(r["question"]): r for r in counselling}
    by_answer = {_match_key(r["answer"]): r for r in counselling}
    matched_seq_nums: set[int] = set()
    records = []

    for _, row in frame.iterrows():
        question = _clean_text(row.iloc[4]) or _clean_text(row.iloc[3])
        answer = _clean_text(row.iloc[5])
        if not question or not answer:
            continue
        matched = by_question.get(_match_key(question)) or by_answer.get(_match_key(answer))
        if matched:
            matched_seq_nums.add(matched["source_seq_num"])
        similar_questions = list(dict.fromkeys(
            text for text in (_clean_text(row.iloc[i]) for i in range(6, 16)) if text
        ))
        number = int(row.iloc[0])
        category = _clean_text(row.iloc[2]) or None
        q_type = matched.get("q_type") if matched else None
        source_seq_num = matched.get("source_seq_num") if matched else None
        parts = [f"질문: {question}", f"답변: {answer}"]
        if similar_questions:
            parts.append(f"유사질문: {', '.join(similar_questions)}")
        records.append({
            "source_type": "general_consultation",
            "source_seq_num": source_seq_num,
            "q_type": q_type,
            "category": category,
            "question": question,
            "answer": answer,
            "keywords": [],
            "similar_questions": similar_questions,
            "page_label": _clean_text(row.iloc[3]) or question,
            "chunk_id": f"general_consultation:{number}",
            "source_url": None,
            "menuIdx": None,
            "text": "\n".join(parts),
        })
    return records, matched_seq_nums


def load_handcrafted_faqs(
    general_path: Path | None = None,
    counselling_path: Path | None = None,
) -> tuple[list[dict], dict[str, int]]:
    """일반상담과 counselling_info를 모두 포함한 기존 FAQ KB를 만든다.

    병합 규칙:
    - 양쪽에 존재: 일반상담 레코드에 counselling seq_num/q_type을 결합
    - 일반상담에만 존재: q_type/source_seq_num은 null인 채 포함
    - counselling에만 존재: category/keywords는 null·빈 목록인 채 포함

    반환값은 통합 레코드와 원천별 처리 건수이다.
    """
    general = general_path or Path(os.getenv("TTOBAGI_GENERAL_FAQ_PATH", DEFAULT_GENERAL_FAQ))
    counselling_file = counselling_path or Path(
        os.getenv("TTOBAGI_COUNSELLING_FAQ_PATH", DEFAULT_COUNSELLING_FAQ)
    )
    counselling = _read_counselling(counselling_file) if counselling_file.exists() else []
    general_records, matched = (
        _read_general(general, counselling) if general.exists() else ([], set())
    )
    counselling_only = [
        record for record in counselling if record["source_seq_num"] not in matched
    ]
    return general_records + counselling_only, {
        "generalConsultation": len(general_records),
        "counsellingInfo": len(counselling),
        "counsellingOnly": len(counselling_only),
    }


def _question_key(record: dict) -> str | None:
    """승인 FAQ가 같은 질문의 기존 FAQ를 대체할 때 사용할 정규화 키이다."""
    question = record.get("question")
    if not question:
        return None
    return re.sub(r"\s+", "", str(question)).casefold()


def merge_kb(base_records: list[dict], approved_faqs: list[dict]) -> list[dict]:
    """기존 FAQ/문서 KB에 gold_faq를 추가한다.

    원천 seq_num 또는 질문이 같은 경우에는 운영자가 검수한 gold_faq를 우선하고,
    나머지 일반상담·counselling FAQ는 모두 유지한다.
    """
    approved_keys = {
        key for record in approved_faqs
        if (key := _question_key(record)) is not None
    }
    approved_sources = {
        record["source_seq_num"] for record in approved_faqs
        if record.get("source_seq_num") is not None
    }
    retained_base = [
        record for record in base_records
        if _question_key(record) not in approved_keys
        and (record.get("source_seq_num") is None
             or record["source_seq_num"] not in approved_sources)
    ]
    return retained_base + approved_faqs


def sanitize_kb(sources: list[dict]) -> list[dict]:
    """Clean every KB source before embedding and answer prompting."""
    records = []
    for source in sources:
        record = dict(source)
        for field in ("text", "question", "answer"):
            if field in record:
                record[field] = _clean_text(record[field])
        if record.get("text"):
            records.append(record)
    return records


def load_current_kb(
    base_path: Path,
    connection_factory: ConnectionFactory | None = None,
    general_path: Path | None = None,
    counselling_path: Path | None = None,
) -> tuple[list[dict], dict[str, int]]:
    """검색 실행 시점의 전체 RAG KB와 출처별 건수를 반환한다.

    최종 입력 구성:
    선택 문서 JSONL + 일반상담 + counselling_info + 활성 gold_faq
    """
    base = load_base_kb(base_path)
    handcrafted, source_counts = load_handcrafted_faqs(general_path, counselling_path)
    # Keep active operating IDs searchable even when source spreadsheets exist.
    # merge_kb replaces the corresponding file records with authoritative DB copies.
    approved = load_approved_faqs(connection_factory, include_source_copies=True)
    # File records are not authoritative about active operating IDs.
    for record in base + handcrafted:
        record.pop('faq_id', None)
    merged = sanitize_kb(merge_kb(base + handcrafted, approved))
    return merged, {
        "base": len(base),
        **source_counts,
        "approvedFaq": len(approved),
        "merged": len(merged),
    }
