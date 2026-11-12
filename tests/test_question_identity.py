"""Specification TC-01..10, TC-13/14, with controlled semantic decisions."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import pandas as pd

from services.question_identity import group_questions, merge_partitions, diverse_indices, IntentVerifier
from services.faq_matching import decide_match


def same(left, right):
    return dict(decision='same', reason='same answer intent')


class QuestionIdentityTest(unittest.TestCase):
    def test_19000_synthetic_logs_preserve_frequency_and_inverse_mapping(self):
        texts = [f'질문 {i % 190}' for i in range(19000)]
        groups, inverse = group_questions(texts, list(range(19000)))
        self.assertEqual(len(groups), 190)
        self.assertEqual(sum(g['frequency'] for g in groups), 19000)
        self.assertEqual({log for g in groups for log in g['logIds']}, set(range(19000)))
        self.assertEqual([groups[i]['normalizedText'] for i in inverse], texts)

    def test_four_ventilation_logs_and_hundred_duplicates(self):
        for count in (4, 100):
            groups, inverse = group_questions(['환기요청'] * count, list(range(count)))
            self.assertEqual(len(groups), 1)
            self.assertEqual(groups[0]['frequency'], count)
            self.assertEqual(groups[0]['logIds'], list(range(count)))
            self.assertEqual(len(set(np.array([38])[inverse])), 1)

    def test_unicode_and_whitespace_identity(self):
        groups, _ = group_questions(['환기  요청', '환기 요청', '환기 요청'], [1, 2, 3])
        self.assertEqual(len(groups), 1)

    def test_same_intent_merges_but_opposite_intent_does_not(self):
        def intent(a, b):
            return same(a, b) if b == '냉방 감소' else dict(decision='different', reason='opposite direction')
        parts, _ = merge_partitions(['너무 추워요', '냉방 감소', '너무 더워요'],
                                    [[1, 0], [1, .1], [1, .05]], intent)
        self.assertEqual(parts, [[0, 1], [2]])

    def test_cluster_name_cannot_authorize_merge(self):
        parts, _ = merge_partitions(['분실물 찾기', '분실물 신고'], [[1, 0], [1, 0]],
                                    lambda a, b: dict(decision='different', reason='different procedure'))
        self.assertEqual(parts, [[0], [1]])

    def test_no_transitive_chain_merging(self):
        angles = np.deg2rad([0, 25, 50])
        vectors = np.column_stack((np.cos(angles), np.sin(angles)))
        parts, _ = merge_partitions(['A', 'B', 'C'], vectors, same)
        self.assertEqual(parts, [[0, 1], [2]])

    def test_whole_partition_revalidation(self):
        def intent(a, b):
            return dict(decision='unknown', reason='mixed conditions') if isinstance(a, list) else same(a, b)
        parts, _ = merge_partitions(['A', 'B', 'C'], [[1, 0]] * 3, intent)
        self.assertEqual(parts, [[0, 1], [2]])

    def test_mmr_omits_near_duplicates_and_limits_samples(self):
        selected = diverse_indices([[1, 0], [1, .001], [.5, .5], [0, 1]], limit=3)
        self.assertEqual(len(selected), 3)
        self.assertFalse(0 in selected and 1 in selected)
        self.assertLessEqual(len(diverse_indices(np.eye(20))), 12)

    def test_duplicate_faqs_merge_with_all_logs_and_clusters(self):
        from services.faq_generator import consolidate, build_prompt
        candidates = [dict(candidateId=f'candidate:{cid}', clusterLabel=cid,
                           finalClusterIds=[cid], logIds=ids, tag='FAQ', synonyms=[],
                           standardQuestion='환기 요청', similarQuestions=['환기 요청'])
                      for cid, ids in [(3, [1, 2]), (8, [2, 4])]]
        merged, _ = consolidate(candidates, [[1, 0], [1, 0]], same)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]['finalClusterIds'], [3, 8])
        self.assertEqual(merged[0]['logIds'], [1, 2, 4])
        self.assertEqual(merged[0]['clusterSize'], 3)
        prompt = build_prompt(3, 100, [], ['환기 요청'] * 100)
        self.assertEqual(prompt.count('  - 환기 요청'), 1)

    def test_small_duplicate_input_maps_every_log_to_noise(self):
        from services import clustering as cl
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            frame = pd.DataFrame(dict(logId=list(range(100)), normalizedForClustering=['환기요청'] * 100))
            with patch.object(cl, 'DATA_DIR', data), patch.object(cl, 'load', return_value=frame), \
                 patch.object(cl, 'plot_2d', side_effect=lambda *a: a[-1].write_bytes(b'plot')), \
                 patch.object(cl, 'load_model', side_effect=AssertionError('No model needed')):
                cl.main()
            rows = pd.read_csv(data / 'clusters.csv')
            self.assertEqual(len(rows), 100)
            self.assertEqual(set(rows.clusterId), {-1})
            groups = json.loads((data / 'question_groups.json').read_text(encoding='utf-8'))['groups']
            self.assertEqual(groups[0]['frequency'], 100)
            self.assertEqual(len(set(rows.questionGroupId)), 1)

    def test_intent_model_failure_is_unknown(self):
        with patch('services.llm_runtime.load_chat_model', side_effect=RuntimeError('unavailable')):
            decision = IntentVerifier()('A', 'B')
        self.assertEqual(decision['decision'], 'unknown')
        self.assertIn('unavailable', decision['reason'])

    def test_lazy_intent_verifier_passes_configured_model_identity(self):
        with patch('services.llm_runtime.model_id', return_value='configured/model'), \
             patch('services.llm_runtime.load_chat_model', autospec=True, return_value=('tok', 'model')) as load, \
             patch('services.llm_runtime.generate_chat', return_value='{"decision":"same","reason":"동일 의도"}'):
            verifier = IntentVerifier()
            self.assertEqual(verifier('환기 요청', '환기해주세요')['decision'], 'same')
            self.assertEqual(verifier('환기 요청', '환기해주세요')['decision'], 'same')
            load.assert_called_once_with('configured/model')

    def test_operational_faq_below_document_still_expands(self):
        result = decide_match('질문', [dict(source_type='base_kb', rerank_score=.99),
            dict(source_type='approved_faq', faq_id=123, rerank_score=.87, dense_score=.9)], same)
        self.assertEqual(result['candidateType'], 'EXPAND')
        self.assertEqual(result['matchedFaqs'][0]['matchedFaqId'], 123)
        self.assertEqual(result['matchEvidence'][0]['retrievalScore'], .9)

    def test_missing_id_boundary_unknown_and_different(self):
        for faq_id, score, decision, expected in [
            (None, .95, 'same', 'ID_LINK_ERROR'), (0, .95, 'same', 'ID_LINK_ERROR'),
            (123, .80, 'same', 'REVIEW_REQUIRED'), (123, .79, 'same', 'REVIEW_REQUIRED'),
            (123, .95, 'unknown', 'REVIEW_REQUIRED'), (123, .95, 'different', 'INTENT_DIFFERENT')]:
            with self.subTest(faq_id=faq_id, score=score, decision=decision):
                result = decide_match('질문', [dict(source_type='approved_faq', faq_id=faq_id, rerank_score=score)],
                    lambda a, b: dict(decision=decision, reason='test'))
                self.assertEqual(result['candidateType'], 'NEW')
                self.assertEqual(result['matchEvidence'][0]['matchStatus'], expected)
                self.assertEqual(result['matchedFaqs'], [])


if __name__ == '__main__':
    unittest.main()
