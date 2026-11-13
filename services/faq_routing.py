"""Route individual questions before discovering new FAQ clusters."""
from collections import defaultdict
from functools import lru_cache
import math
import re
import numpy as np

from services.faq_matching import FAQ_SOURCES, valid_faq_id
from services.question_identity import normalize, unit

AMBIGUOUS_EXPRESSIONS = {'지하철', '공항철도', '카드', '시설', '서울'}


def key(text):
    return re.sub(r'[^\w]+', '', normalize(text or '').casefold())


@lru_cache(maxsize=1)
def korean_analyzer():
    from kiwipiepy import Kiwi
    return Kiwi(num_workers=1)


def keyword_words(text):
    """Keep word boundaries and remove only particles identified by Kiwi."""
    text = normalize(text or '').casefold()
    words = list(re.finditer(r'\w+', text))
    if not re.search(r'[가-힣]', text):
        return tuple(word.group() for word in words)
    tokens = iter(korean_analyzer().tokenize(text))
    token = next(tokens, None)
    result = []
    for word in words:
        start, end = word.span()
        parts = []
        while token is not None and token.start < end:
            if token.start >= start and token.end <= end:
                parts.append(token)
            token = next(tokens, None)
        while parts and parts[-1].tag.startswith('J'):
            end = parts.pop().start
        result.append(text[start:end] or word.group())
    return tuple(result)


def route_questions(texts, kb, rank, verify, threshold, margin):
    if not math.isfinite(threshold) or not 0 < threshold - margin < threshold + margin < 1:
        raise ValueError('Invalid FAQ routing threshold or margin')
    titles, keywords, synonyms = (defaultdict(set) for _ in range(3))
    active = {r['faq_id']: r for r in kb if r.get('source_type') == 'approved_faq'
              and valid_faq_id(r.get('faq_id'))}
    for faq_id, row in active.items():
        titles[key(row.get('question'))].add(faq_id)
        for term in row.get('keywords', []):
            words = keyword_words(term)
            if words:
                keywords[words].add(faq_id)
        for term in row.get('synonyms', []):
            if key(term):
                synonyms[key(term)].add(faq_id)

    keyword_lengths = sorted({len(words) for words in keywords})
    lexical = []
    resolved = {}
    semantic_indices = []
    keyword_outcomes = {}
    for index, text in enumerate(texts):
        normalized = key(text)
        exact = titles.get(normalized, set())
        aliases = synonyms.get(normalized, set())
        words = keyword_words(text) if keywords else ()
        keyword_ids = set()
        for length in keyword_lengths:
            for start in range(len(words) - length + 1):
                keyword_ids.update(keywords.get(words[start:start + length], ()))
        ids = exact | aliases | keyword_ids
        lexical.append((exact, aliases, keyword_ids, ids))
        if normalized in AMBIGUOUS_EXPRESSIONS:
            resolved[index] = dict(status='REVIEW', reason='AMBIGUOUS_EXPRESSION')
            continue
        if len(ids) == 1:
            faq_id = next(iter(ids))
            path = 'FAQ_TITLE' if faq_id in exact else 'APPROVED_SYNONYM' if faq_id in aliases else 'KEYWORD'
            if path != 'KEYWORD':
                resolved[index] = dict(status='MATCHED', faqId=faq_id, path=path,
                                       reason='UNIQUE_APPROVED_EXPRESSION')
                continue
            row = active[faq_id]
            outcome = verify(text, {'question': row['question'], 'answer': row['answer']})['decision']
            keyword_outcomes[index] = outcome
            if outcome == 'same':
                resolved[index] = dict(status='MATCHED', faqId=faq_id, path=path,
                                       reason='SAME_APPLICABLE_ANSWER')
                continue
        semantic_indices.append(index)

    ranked = dict(zip(semantic_indices, rank([texts[i] for i in semantic_indices], kb), strict=True))
    decisions = []
    for index, text in enumerate(texts):
        if index in resolved:
            decisions.append(resolved[index])
            continue
        exact, aliases, keyword_ids, ids = lexical[index]
        confirmed = {}
        review = False
        candidates = [(active[faq_id], 'APPROVED_SYNONYM' if faq_id in aliases else
                       'FAQ_TITLE' if faq_id in exact else 'KEYWORD') for faq_id in sorted(ids)]
        candidates += [(row, 'SEMANTIC') for row in ranked[index]
                       if row.get('source_type') in FAQ_SOURCES
                       and row['rerank_score'] >= threshold - margin
                       and row.get('faq_id') not in ids]
        for row, path in candidates:
            faq_id = row.get('faq_id')
            outcome = (keyword_outcomes[index] if path == 'KEYWORD' and index in keyword_outcomes
                       else verify(text, {'question': row.get('question'), 'answer': row.get('answer'),
                                          'text': row.get('text')})['decision'])
            if outcome == 'same' and faq_id in active and (
                    path != 'SEMANTIC' or row['rerank_score'] > threshold + margin):
                confirmed[faq_id] = path
            elif outcome != 'different':
                review = True
        if len(confirmed) == 1 and not review:
            faq_id, path = next(iter(confirmed.items()))
            decisions.append(dict(status='MATCHED', faqId=faq_id, path=path, reason='SAME_APPLICABLE_ANSWER'))
        elif confirmed or review or len(exact | aliases) > 1:
            decisions.append(dict(status='REVIEW', reason='FAQ_MATCH_UNCERTAIN'))
        else:
            decisions.append(dict(status='UNMATCHED', reason='NO_CONFIRMED_FAQ'))
    return decisions, active


def split_by_intent(indices, texts, verify):
    """Each addition must be compatible with every accepted question in the group."""
    partitions, deferred = [], []
    for index in indices:
        compatible = []
        uncertain = False
        for partition in partitions:
            for member in partition:
                decision = verify(texts[index], texts[member])['decision']
                if decision == 'unknown':
                    uncertain = True
                if decision != 'same':
                    break
            else:
                compatible.append(partition)
        if uncertain or len(compatible) > 1:
            deferred.append(index)
        elif compatible:
            compatible[0].append(index)
        elif verify(texts[index], texts[index])['decision'] == 'same':
            partitions.append([index])
        else:
            deferred.append(index)
    return partitions, deferred


def recover_noise(indices, texts, embeddings, verify, *, neighbors=5, threshold=.65):
    """Use a bounded nearest-neighbor search, then require agreement across each merge."""
    if len(indices) < 2:
        return []
    vectors = unit(embeddings[indices])
    edges = {}
    for start in range(0, len(vectors), 128):
        scores = vectors[start:start + 128] @ vectors.T
        for offset, row in enumerate(scores):
            left = start + offset
            row[left] = -np.inf
            nearest = np.argsort(-row, kind='stable')[:neighbors]
            for right in nearest:
                if row[right] >= threshold:
                    pair = tuple(sorted((left, int(right))))
                    edges[pair] = float(row[right])

    partitions = {i: [i] for i in range(len(indices))}
    owner = list(partitions)
    verified = {}

    def agrees(left, right):
        pair = tuple(sorted((left, right)))
        if pair not in verified:
            verified[pair] = (vectors[left] @ vectors[right] >= threshold and
                              verify(texts[indices[left]], texts[indices[right]])['decision'] == 'same')
        return verified[pair]

    for left, right in sorted(edges, key=lambda pair: (-edges[pair], pair)):
        first, second = owner[left], owner[right]
        if first == second:
            continue
        if all(agrees(a, b) for a in partitions[first] for b in partitions[second]):
            for member in partitions.pop(second):
                owner[member] = first
                partitions[first].append(member)
    return [sorted(int(indices[i]) for i in members)
            for members in partitions.values() if len(members) >= 2]


def expansion_candidate(label, faq, groups):
    question = faq['question']
    samples = list(dict.fromkeys(g['normalizedText'] for g in groups))
    known = {key(question), *(key(s) for s in faq.get('synonyms', []))}
    synonyms = []
    for text in samples:
        normalized = key(text)
        if normalized and normalized not in known and len(text) <= 100:
            synonyms.append(dict(text=text, type='SYNONYM'))
            known.add(normalized)
    log_ids = [int(log_id) for group in groups for log_id in group['logIds']]
    candidate_id = f'candidate:{label}'
    return dict(clusterLabel=label, clusterSize=len(log_ids), tag='FAQ', candidateType='EXPAND',
                standardQuestion=question, answerDraft=faq['answer'], qType=faq.get('q_type'),
                category=faq.get('category'), matchedFaqs=[dict(matchedFaqId=faq['faq_id'],
                matchedFaqSeqNum=faq.get('source_seq_num'), matchScore=1.0)],
                synonyms=synonyms[:10], similarQuestions=samples[:12], logIds=log_ids,
                candidateId=candidate_id, sourceCandidateIds=[candidate_id], finalClusterIds=[label],
                matchStatus='CONFIRMED', matchDecisionReason='질문별로 동일한 운영 FAQ를 확인했습니다.',
                matchEvidence=[dict(logIds=g['logIds'], matchPath=g['routing']['path'],
                                    reason=g['routing']['reason']) for g in groups])
