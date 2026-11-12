"""Model-free operational FAQ decision policy (sigmoid scores, not probabilities)."""
import math

FAQ_SOURCES = {'general_consultation', 'counselling_info', 'approved_faq'}


def valid_faq_id(value):
    return type(value) is int and value > 0


def decide_match(question, ranked, verify, threshold=.80, margin=.02):
    evidence, confirmed = [], []
    for item in ranked:
        if item.get('source_type') not in FAQ_SOURCES:
            continue
        score = item.get('rerank_score', 0)
        if not math.isfinite(score) or not 0 <= score <= 1:
            raise ValueError('Expected normalized sigmoid reranker score')
        if score < threshold - margin:
            continue
        decision = verify(question, {'question': item.get('question'), 'answer': item.get('answer'),
                                     'text': item.get('text')})
        valid = valid_faq_id(item.get('faq_id'))
        status = ('INTENT_DIFFERENT' if decision['decision'] == 'different' else
                  'ID_LINK_ERROR' if not valid else
                  'REVIEW_REQUIRED' if score <= threshold + margin or decision['decision'] != 'same' else
                  'CONFIRMED')
        evidence.append(dict(matchedFaqId=item.get('faq_id'), retrievalScore=item.get('dense_score'),
                             rerankerScore=score, matchStatus=status,
                             matchDecisionReason=decision['reason'], sourceChunkId=item.get('chunk_id')))
        if status == 'CONFIRMED':
            confirmed.append(item)
    best = confirmed[0] if confirmed else None
    review = any(e['matchStatus'] in {'ID_LINK_ERROR', 'REVIEW_REQUIRED'} for e in evidence)
    return dict(candidateType='EXPAND' if best else 'NEW',
                qType=best.get('q_type') if best else None,
                category=best.get('category') if best else None,
                matchStatus='CONFIRMED' if best else 'REVIEW_REQUIRED' if review else 'NEW',
                matchDecisionReason='유효한 운영 FAQ와 답변 의도가 일치합니다.' if best else
                '의도가 불명확하거나 운영 FAQ ID 연결 확인이 필요합니다.' if review else '동일한 답변 의도의 운영 FAQ가 없습니다.',
                matchEvidence=evidence,
                matchedFaqs=[dict(matchedFaqId=item['faq_id'], matchedFaqSeqNum=item.get('source_seq_num'),
                                 matchScore=round(item['rerank_score'], 4)) for item in confirmed])
