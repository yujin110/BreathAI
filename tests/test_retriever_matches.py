"""Regression coverage for FAQ identity and expansion decisions, without models/DB."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch


class RetrieverMatchesTest(unittest.TestCase):
    def run_retrieval(self, top):
        deps = {
            'numpy': types.ModuleType('numpy'),
            'torch': types.SimpleNamespace(
                cuda=types.SimpleNamespace(is_available=lambda: False),
                nn=types.SimpleNamespace(Sigmoid=lambda: None)),
            'sentence_transformers': types.SimpleNamespace(
                SentenceTransformer=Mock(), CrossEncoder=Mock()),
        }
        spec = importlib.util.spec_from_file_location(
            '_test_retriever_matches', Path(__file__).parents[1] / 'services/retriever.py')
        module = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, deps):
            spec.loader.exec_module(module)
        candidates = [dict(clusterLabel=1, clusterSize=19, tag='FAQ', standardQuestion='Question')]
        with tempfile.TemporaryDirectory() as tmp:
            module.FAQ_JSON = Path(tmp) / 'candidates.json'
            module.OUTPUT_JSON = Path(tmp) / 'retrieval.json'
            module.load_kb = lambda: [{}]
            module.build_or_load_index = lambda kb: None
            module.load_faq_candidates = lambda: candidates
            module.load_similar_questions = lambda: {}
            module.search = lambda **kwargs: []
            module.rerank = lambda **kwargs: top
            module.run_for_faqs()
            record = json.loads(module.OUTPUT_JSON.read_text(encoding='utf-8'))['faqRetrieval'][0]
            saved = json.loads(module.FAQ_JSON.read_text(encoding='utf-8'))['faqCandidates'][0]
        self.assertEqual(saved['matchedFaqs'], record['matchedFaqs'])
        self.assertEqual(saved['candidateType'], record['candidateType'])
        return record

    def test_approved_faq_expands_with_or_without_source_seq(self):
        for seq in (45, None):
            with self.subTest(seq=seq):
                result = self.run_retrieval([dict(source_type='approved_faq', faq_id=123,
                    source_seq_num=seq, rerank_score=.87, q_type=4, category='Category')])
                self.assertEqual(result['candidateType'], 'EXPAND')
                self.assertEqual(result['qType'], 4)
                self.assertEqual(result['matchedFaqs'], [dict(
                    matchedFaqId=123, matchedFaqSeqNum=seq, matchScore=.87)])

    def test_missing_operational_id_is_new_even_with_lower_ranked_approved_faq(self):
        for seq in (45, None):
            with self.subTest(seq=seq):
                result = self.run_retrieval([
                    dict(source_type='general_consultation', source_seq_num=seq, rerank_score=.95),
                    dict(source_type='approved_faq', faq_id=123, rerank_score=.87)])
                self.assertEqual(result['candidateType'], 'NEW')
                self.assertIsNone(result['qType'])
                self.assertIsNone(result['category'])
                self.assertEqual(result['matchedFaqs'][0], dict(
                    matchedFaqId=None, matchedFaqSeqNum=seq, matchScore=.95))
                self.assertEqual(result['matchedFaqs'][1]['matchedFaqId'], 123)

    def test_threshold_documents_and_empty_search_still_produce_new(self):
        for top in ([], [dict(source_type='approved_faq', faq_id=123, rerank_score=.79)],
                    [dict(source_type='base_kb', rerank_score=.99)]):
            with self.subTest(top=top):
                result = self.run_retrieval(top)
                self.assertEqual(result['candidateType'], 'NEW')
                self.assertEqual(result['matchedFaqs'], [])


if __name__ == '__main__':
    unittest.main()
