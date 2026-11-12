"""또타24 FAQ 발굴 — KoSimCSE 임베딩 + UMAP + HDBSCAN + c-TF-IDF.

입력:  dataset/preprocessed_full.csv  (preprocess.py 산출물, 전체 행)
출력:  dataset/embeddings.npy         (N × 768 임베딩 행렬)
       dataset/clusters.csv           (logId, clusterId, umap_x, umap_y, text)
       dataset/cluster_summary.csv    (clusterId, size, isNoise, topKeywords)
       dataset/cluster_keywords.json  (clusterId → keywords[])
       dataset/clusters_umap2d.png    (UMAP 2D 산점도)

설계 원칙:
  - 파이프라인에서 선택한 대상만 입력. HDBSCAN 노이즈 판정은 별도.
  - 진짜 정보 0인 행(정규화 후 길이 ≤1)만 임베딩 전 제외.
  - 라벨은 평가 단계의 ground truth로만 사용.
"""
from __future__ import annotations

import json
import os
import time
import unicodedata
from collections import defaultdict
from pathlib import Path
from services.runtime_config import runtime_path
from services.analysis_progress import report as progress, measure
from services.keyword_policy import (
    KOREAN_STOPWORDS, DOMAIN_STOPWORDS, STOPWORDS, get_policy, write_audit,
)

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import CountVectorizer, TfidfTransformer


def _nfd(p: Path) -> Path:
    """NFD(자모 분리) 디렉토리명 정규화."""
    return Path(unicodedata.normalize('NFD', str(p)))


# 0. 경로/모델/하이퍼파라미터
DATA_DIR = runtime_path('TTOBAGI_DATA_DIR', _nfd(Path(__file__).resolve().parent.parent / 'dataset'))
INPUT_CSV = DATA_DIR / 'preprocessed_full.csv'

MODEL_NAME = os.getenv('TTOBAGI_EMBEDDING_MODEL', 'BM-K/KoSimCSE-roberta')
BATCH_SIZE = 32
MAX_LENGTH = 128

UMAP_CLUSTER_DIM = 10
UMAP_VIZ_DIM = 2
UMAP_NEIGHBORS = 15

HDBSCAN_MIN_CLUSTER_SIZE = int(os.getenv('TTOBAGI_MIN_CLUSTER_SIZE', '5'))
HDBSCAN_MIN_SAMPLES = int(os.getenv('TTOBAGI_MIN_SAMPLES', '2'))
if HDBSCAN_MIN_CLUSTER_SIZE < 2 or HDBSCAN_MIN_SAMPLES < 1:
    raise ValueError('min_cluster_size must be >= 2 and min_samples >= 1')

TOP_N_KEYWORDS = 7
RANDOM_STATE = 42

# 사전 필터: 정규화 후 의미 토큰 1개 이하면 임베딩 의미 없음 (이모티콘만, "ㅋ", "." 등)
MIN_NORMALIZED_LEN = 2


# 1. 데이터 로드 + 사전 필터
def load() -> pd.DataFrame:
    df = pd.read_csv(INPUT_CSV, encoding='utf-8-sig', keep_default_na=False)
    before = len(df)
    df = df[df['normalizedForClustering'].notna() &
            (df['normalizedForClustering'].astype(str).str.len() >= MIN_NORMALIZED_LEN)
           ].reset_index(drop=True)
    print(f'사전 필터: {before}행 → {len(df)}행 (정규화 길이 < {MIN_NORMALIZED_LEN} 제외 {before - len(df)}건)')
    return df


# 2. KoSimCSE 임베딩 — [CLS] pooling
def load_model():
    import torch
    from transformers import AutoTokenizer, AutoModel
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModel.from_pretrained(MODEL_NAME).to(device).eval()
    return tokenizer, model


def encode(texts: list[str], tokenizer, model, *, progress_enabled=True) -> np.ndarray:
    import torch
    with torch.no_grad():
        return _encode(texts, tokenizer, model, progress_enabled=progress_enabled)


def _encode(texts: list[str], tokenizer, model, *, progress_enabled=True) -> np.ndarray:
    all_emb = []
    for i in range(0, len(texts), BATCH_SIZE):
        batch = texts[i:i+BATCH_SIZE]
        inp = tokenizer(batch, padding=True, truncation=True,
                        max_length=MAX_LENGTH, return_tensors='pt').to(model.device)
        out = model(**inp)
        emb = out.last_hidden_state[:, 0, :]
        all_emb.append(emb.cpu().numpy())
        if progress_enabled:
            progress('EMBEDDING', min(i + BATCH_SIZE, len(texts)), len(texts), 'LOG')
    return np.vstack(all_emb)


# 3. UMAP — 클러스터링용 10D + 시각화용 2D
def reduce_dims(embeddings: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    import umap
    count = len(embeddings)
    neighbors, dimensions = min(UMAP_NEIGHBORS, count - 1), min(UMAP_CLUSTER_DIM, count - 2)
    progress('DIMENSION_REDUCTION', 0, 2, 'STEP')
    reducer_clu = umap.UMAP(n_components=dimensions, n_neighbors=neighbors,
                            min_dist=0.0, metric='cosine', random_state=RANDOM_STATE)
    emb_clu = reducer_clu.fit_transform(embeddings)
    progress('DIMENSION_REDUCTION', 1, 2, 'STEP')

    reducer_viz = umap.UMAP(n_components=UMAP_VIZ_DIM, n_neighbors=neighbors,
                            init='random' if count <= 3 else 'spectral',
                            min_dist=0.1, metric='cosine', random_state=RANDOM_STATE)
    emb_viz = reducer_viz.fit_transform(embeddings)
    progress('DIMENSION_REDUCTION', 2, 2, 'STEP')
    return emb_clu, emb_viz


# 4. HDBSCAN — 노이즈는 -1
def cluster(emb_clu: np.ndarray) -> np.ndarray:
    import hdbscan
    clusterer = hdbscan.HDBSCAN(
        min_cluster_size=HDBSCAN_MIN_CLUSTER_SIZE,
        min_samples=HDBSCAN_MIN_SAMPLES,
        metric='euclidean',
        cluster_selection_method='eom',
    )
    return clusterer.fit_predict(emb_clu)


# 5. c-TF-IDF — 클러스터별 키워드
def korean_tokenizer(text: str) -> list[str]:
    return get_policy().tokenize(text)[0]


def extract_keywords(texts: list[str], cluster_labels: np.ndarray, *, policy=None, audit_path=None
                     ) -> tuple[dict[int, list[str]], dict[int, list[str]]]:
    policy = policy or get_policy()
    if len(texts) != len(cluster_labels):
        raise ValueError('Keyword texts and cluster labels must have equal length')
    cluster_docs: dict[int, list[str]] = defaultdict(list)
    cluster_tokens = defaultdict(list)
    excluded = 0
    for t, cid in zip(texts, cluster_labels):
        cluster_docs[int(cid)].append(t)
        tokens, removed = policy.tokenize(t)
        cluster_tokens[int(cid)].extend(tokens)
        excluded += removed

    cluster_ids = sorted(cluster_docs.keys())
    token_docs = [cluster_tokens[cid] for cid in cluster_ids]
    if not any(token_docs):
        keywords = {cid: [] for cid in cluster_ids}
        if audit_path is not None:
            write_audit(audit_path, policy, excluded, keywords)
        return keywords, dict(cluster_docs)

    cv = CountVectorizer(analyzer=lambda tokens: tokens, min_df=1, lowercase=False)
    counts = cv.fit_transform(token_docs)
    tfidf = TfidfTransformer(smooth_idf=True, sublinear_tf=True).fit_transform(counts)

    vocab = cv.get_feature_names_out()
    keywords: dict[int, list[str]] = {}
    for i, cid in enumerate(cluster_ids):
        row = tfidf[i].toarray().flatten()
        top_idx = row.argsort()[::-1][:TOP_N_KEYWORDS]
        keywords[cid] = [vocab[j] for j in top_idx if row[j] > 0]
    if audit_path is not None:
        write_audit(audit_path, policy, excluded, keywords)
    return keywords, dict(cluster_docs)


# 6. 시각화
def plot_2d(emb_viz: np.ndarray, cluster_labels: np.ndarray, n_clusters: int, out_path: Path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(11, 7))
    unique_cids = sorted(set(cluster_labels.tolist()))
    cmap = plt.cm.get_cmap('tab20', max(len(unique_cids) - 1, 1))

    color_idx = 0
    for cid in unique_cids:
        mask = cluster_labels == cid
        if cid == -1:
            ax.scatter(emb_viz[mask, 0], emb_viz[mask, 1], c='lightgray',
                       s=15, alpha=0.4, label=f'noise (n={mask.sum()})')
        else:
            ax.scatter(emb_viz[mask, 0], emb_viz[mask, 1], c=[cmap(color_idx)],
                       s=25, alpha=0.8, label=f'C{cid} (n={mask.sum()})')
            color_idx += 1

    ax.set_title(f'HDBSCAN clusters on UMAP 2D ({n_clusters} clusters + noise)')
    ax.set_xlabel('UMAP-1')
    ax.set_ylabel('UMAP-2')
    if len(unique_cids) <= 20:
        ax.legend(loc='center left', bbox_to_anchor=(1.01, 0.5), fontsize=8)
    ax.grid(True, alpha=0.2)
    plt.tight_layout()
    plt.savefig(out_path, dpi=150, bbox_inches='tight')
    plt.close()


# 7. 산출물 저장
def save(df: pd.DataFrame, embeddings: np.ndarray, emb_viz: np.ndarray,
         cluster_labels: np.ndarray, keywords: dict[int, list[str]],
         cluster_docs: dict[int, list[str]]) -> dict[str, Path]:
    paths = {
        'emb': DATA_DIR / 'embeddings.npy',
        'clu': DATA_DIR / 'clusters.csv',
        'sum': DATA_DIR / 'cluster_summary.csv',
        'kw': DATA_DIR / 'cluster_keywords.json',
    }

    np.save(paths['emb'], embeddings)

    output = pd.DataFrame({
        'logId': df['logId'].tolist(),
        'clusterId': cluster_labels.astype(int),
        'umap_x': emb_viz[:, 0],
        'umap_y': emb_viz[:, 1],
        'text': df['normalizedForClustering'].astype(str).tolist(),
    })
    for field in ('questionGroupId', 'initialClusterId'):
        if field in df:
            output[field] = df[field].tolist()
    output.to_csv(paths['clu'], index=False, encoding='utf-8-sig')

    summary = pd.DataFrame([
        {
            'clusterId': cid,
            'size': len(cluster_docs[cid]),
            'uniqueSize': len(set(cluster_docs[cid])),
            'isNoise': cid == -1,
            'topKeywords': ', '.join(keywords[cid][:5]),
        }
        for cid in sorted(keywords.keys())
    ]).sort_values(by=['isNoise', 'size'], ascending=[True, False]).reset_index(drop=True)
    summary.to_csv(paths['sum'], index=False, encoding='utf-8-sig')

    with open(paths['kw'], 'w', encoding='utf-8') as f:
        json.dump({str(k): v for k, v in keywords.items()}, f, ensure_ascii=False, indent=2)

    return paths


# 8. 리포트
def report(cluster_labels: np.ndarray, keywords: dict[int, list[str]],
           cluster_docs: dict[int, list[str]]):
    n_clusters = len(set(cluster_labels.tolist())) - (1 if -1 in cluster_labels else 0)
    n_noise = int((cluster_labels == -1).sum())
    print(f'클러스터: {n_clusters}개  /  노이즈(-1): {n_noise}건 ({n_noise/len(cluster_labels):.1%})')
    print()
    items = sorted(
        [(cid, len(cluster_docs[cid])) for cid in keywords],
        key=lambda x: (x[0] == -1, -x[1])
    )[:13]
    for cid, size in items:
        kws = keywords[cid][:5]
        tag = 'noise   ' if cid == -1 else f'cluster {cid:>2}'
        print(f'  [{tag}] n={size:>3}  keywords: {kws}')
        for s in cluster_docs[cid][:3]:
            print(f'      · {s[:70]}')
        print()


def main() -> None:
    policy = get_policy()  # Fail before importing/loading inference models.
    df = load()
    if len(df) == 0:
        from services.analysis_selection import empty_clusters
        empty_clusters(DATA_DIR, policy)
        return
    from services.question_identity import group_questions, IntentVerifier, merge_partitions, unit
    groups, inverse = group_questions(df['normalizedForClustering'].astype(str).tolist(),
                                     df['logId'].tolist())
    texts = [group['normalizedText'] for group in groups]
    print(f'[1/4] KoSimCSE 임베딩 ({MODEL_NAME})')
    progress('EMBEDDING', 0, len(groups), 'LOG')
    t0 = time.time()
    small = len(groups) < max(3, HDBSCAN_MIN_CLUSTER_SIZE, HDBSCAN_MIN_SAMPLES + 1)
    if small:
        # Never run UMAP on a singleton, nor invent a semantic noise cluster.
        embeddings = np.zeros((len(groups), 1))
    else:
        import torch
        with measure('EMBEDDING_MODEL_LOAD'):
            tokenizer, model = load_model()
        with measure('EMBEDDING'):
            embeddings = encode(texts, tokenizer, model)
        del tokenizer, model
        import gc
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    print(f'  shape={embeddings.shape}, {time.time()-t0:.1f}s')

    print(f'[2/4] UMAP 축소 (768 → {UMAP_CLUSTER_DIM}D / {UMAP_VIZ_DIM}D)')
    t0 = time.time()
    with measure('DIMENSION_REDUCTION'):
        if small:
            emb_clu, emb_viz = embeddings, np.zeros((len(groups), 2))
        else:
            emb_clu, emb_viz = reduce_dims(embeddings)
    print(f'  clustering={emb_clu.shape}, viz={emb_viz.shape}, {time.time()-t0:.1f}s')

    print(f'[3/4] HDBSCAN (min_cluster_size={HDBSCAN_MIN_CLUSTER_SIZE})')
    progress('CLUSTERING', 0, 1, 'STEP')
    t0 = time.time()
    with measure('HDBSCAN'):
        cluster_labels = np.full(len(groups), -1, dtype=int) if small else cluster(emb_clu)
    progress('CLUSTERING', 1, 1, 'STEP')
    print(f'  {time.time()-t0:.1f}s')

    initial_labels = cluster_labels.copy()
    ids = sorted(set(cluster_labels.tolist()) - {-1})
    merge_history = []
    if len(ids) > 1:
        members = [[texts[i] for i in np.flatnonzero(initial_labels == cid)] for cid in ids]
        centers = np.asarray([unit(embeddings[initial_labels == cid]).mean(axis=0) for cid in ids])
        verifier = IntentVerifier()
        try:
            partitions, merge_history = merge_partitions(members, centers, verifier)
        except Exception as exc:
            partitions = [[i] for i in range(len(ids))]
            merge_history = [dict(decision='unknown', reason=f'{type(exc).__name__}: {exc}')]
        for partition in partitions:
            for index in partition:
                cluster_labels[initial_labels == ids[index]] = ids[partition[0]]
        del verifier
    for i, group in enumerate(groups):
        group.update(initialClusterId=int(initial_labels[i]), finalClusterId=int(cluster_labels[i]))
    (DATA_DIR / 'question_groups.json').write_text(
        json.dumps({'groups': groups, 'initialClusterIds': ids, 'mergeHistory': merge_history}, ensure_ascii=False, indent=2), encoding='utf-8')
    np.save(DATA_DIR / 'unique_embeddings.npy', embeddings)
    (DATA_DIR / 'clustering_quality.json').write_text(json.dumps({
        'originalLogCount': len(df), 'uniqueQuestionCount': len(groups),
        'uniqueNoiseRate': float(np.mean(cluster_labels == -1)),
        'originalNoiseRate': float(np.mean(cluster_labels[inverse] == -1)),
        'identicalQuestionConsistency': 1.0, 'logPreservationRate': 1.0,
        'embeddingSkippedSmallInput': small,
    }, indent=2), encoding='utf-8')
    df['questionGroupId'] = [groups[i]['questionGroupId'] for i in inverse]
    df['initialClusterId'] = initial_labels[inverse]
    embeddings, emb_viz, cluster_labels = embeddings[inverse], emb_viz[inverse], cluster_labels[inverse]
    texts = [groups[i]['normalizedText'] for i in inverse]
    df['normalizedForClustering'] = texts
    print('[4/4] c-TF-IDF 키워드')
    keywords, cluster_docs = extract_keywords(texts, cluster_labels, policy=policy,
                                             audit_path=DATA_DIR / 'keyword_policy_audit.json')

    print()
    report(cluster_labels, keywords, cluster_docs)

    print('시각화:')
    viz_path = DATA_DIR / 'clusters_umap2d.png'
    n_clusters = len(set(cluster_labels.tolist())) - (1 if -1 in cluster_labels else 0)
    plot_2d(emb_viz, cluster_labels, n_clusters, viz_path)
    print(f'  {viz_path.stat().st_size:>10,} bytes  {viz_path.name}')
    print()

    print('산출물:')
    paths = save(df, embeddings, emb_viz, cluster_labels, keywords, cluster_docs)
    for p in paths.values():
        print(f'  {p.stat().st_size:>10,} bytes  {p.name}')


if __name__ == '__main__':
    main()
