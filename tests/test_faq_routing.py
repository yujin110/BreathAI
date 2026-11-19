import unittest
import numpy as np

from services.faq_routing import expansion_candidate, keyword_words, recover_noise, route_questions, split_by_intent


class FaqRoutingTest(unittest.TestCase):
    def test_noise_recovery_requires_all_pairs_and_excludes_other_intents(self):
        texts = ['라이트 꺼짐', '전등 안 켜짐', '불빛 꺼짐', '출입문 고장', '미태그']
        vectors = np.ones((len(texts), 2))
        allowed = {frozenset((texts[0], texts[1])), frozenset((texts[1], texts[2]))}

        def verify(left, right):
            return {'decision': 'same' if frozenset((left, right)) in allowed else 'different'}

        self.assertEqual([[0, 1]], recover_noise(np.arange(len(texts)), texts, vectors, verify))
        allowed.add(frozenset((texts[0], texts[2])))
        self.assertEqual([[0, 1, 2]], recover_noise(np.arange(len(texts)), texts, vectors, verify))

    def test_particles_reach_intent_verification_without_substring_matching(self):
        faq = dict(faq_id=21, source_type='approved_faq', question='객실 악취 신고',
                   answer='객실 악취 신고 안내', keywords=['악취', '객실 조명'])
        translated = '지하철 칸 안에 악취가 납니다.'
        native = '악취를 신고하고 싶어요.'
        multiword = '객실 조명이 꺼졌어요.'
        unrelated = '악취제거제를 샀어요.'
        queries, verified = [], []

        def rank(texts, _kb):
            queries.extend(texts)
            return [[] for _ in texts]

        def verify(text, _target):
            verified.append(text)
            return dict(decision='different' if text == multiword else 'same')

        decisions, _ = route_questions([translated, native, multiword, unrelated], [faq], rank, verify, .8, .02)
        self.assertEqual([translated, native, multiword], verified)
        self.assertEqual([multiword, unrelated], queries)
        self.assertEqual(['MATCHED', 'MATCHED', 'UNMATCHED', 'UNMATCHED'],
                         [d['status'] for d in decisions])
        self.assertEqual(('조명',), keyword_words('조명으로는'))

    def test_match_review_new_and_approved_expression_round_trip(self):
        faq = dict(faq_id=21, source_seq_num=6099, source_type='approved_faq',
                   question='객실에서 불쾌한 냄새가 날 때', answer='객실 악취 신고 안내', keywords=['악취'])
        texts = ['객실 악취', '객실에서 이상한 냄새가 나요', '전등이 꺼졌어요', '공항철도']

        def rank(queries, kb):
            return [[{**faq, 'rerank_score': .96}] for _ in queries]

        def verify(text, target):
            return dict(decision='different' if '전등' in text else 'same')

        decisions, active = route_questions(texts, [faq], rank, verify, .8, .02)
        self.assertEqual(['MATCHED', 'MATCHED', 'UNMATCHED', 'REVIEW'], [d['status'] for d in decisions])
        self.assertEqual(['KEYWORD', 'SEMANTIC'], [d['path'] for d in decisions[:2]])
        candidate = expansion_candidate(2, active[21], [
            dict(normalizedText=text, logIds=[i + 1], routing=decisions[i]) for i, text in enumerate(texts[:2])])
        self.assertEqual([1, 2], candidate['logIds'])
        self.assertEqual(21, candidate['matchedFaqs'][0]['matchedFaqId'])
        faq['synonyms'] = [s['text'] for s in candidate['synonyms']]
        approved, _ = route_questions([texts[1]], [faq], lambda queries, kb: [], verify, .8, .02)
        self.assertEqual('APPROVED_SYNONYM', approved[0]['path'])

    def test_unlinked_source_and_multiple_verified_targets_require_review(self):
        question = '냄새가 나요'
        source = dict(source_type='counselling_info', question='악취 신고', rerank_score=.99)
        approved = [dict(source_type='approved_faq', faq_id=i, question=f'악취 신고 {i}',
                         synonyms=[question], rerank_score=.99) for i in (1, 2)]
        for kb in ([source], approved):
            decisions, _ = route_questions([question], kb, lambda questions, kb: [kb],
                                           lambda *_: {'decision': 'same'}, .8, .02)
            self.assertEqual('REVIEW', decisions[0]['status'])

    def test_cluster_requires_pairwise_agreement_and_defers_unknown(self):
        texts = ['라이트 꺼짐', '전등 꺼짐', '미태그', '시설 문제']

        def verify(left, right):
            return {'decision': 'unknown' if '시설' in left else
                    'same' if left == right or ('꺼짐' in left and '꺼짐' in right) else 'different'}

        self.assertEqual(([[0, 1], [2]], [3]), split_by_intent(range(4), texts, verify))
