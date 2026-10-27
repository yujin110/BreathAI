"""Boundary tests use fake inference; no model downloads or database access."""
import copy
from contextlib import ExitStack
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

from services.retrieval_contract import bind_retrieval, index_retrieval, select_evidence


def load_source(name, filename, dependencies):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / 'services' / filename)
    module = importlib.util.module_from_spec(spec)
    with patch.dict(sys.modules, dependencies):
        spec.loader.exec_module(module)
    return module


class PipelineRetrievalTest(unittest.TestCase):
    def test_empty_kb_stops_before_expensive_generation(self):
        names = ['answer_regenerator', 'answer_evaluator', 'answer_refiner', 'oos_refresher',
                 'preprocess', 'clustering', 'faq_generator', 'retriever']
        deps = {f'services.{name}': types.ModuleType(f'services.{name}') for name in names}
        deps.update(numpy=types.ModuleType('numpy'), pandas=types.ModuleType('pandas'))
        for name in ['preprocess', 'clustering', 'faq_generator', 'retriever']:
            deps[f'services.{name}'].main = Mock()
        retriever = deps['services.retriever']
        retriever.load_kb = Mock(side_effect=RuntimeError('empty KB'))
        # Importing the real CPU keyword extractor in another test can populate
        # attributes on the services package. Patch both import registries.
        import services
        with ExitStack() as stack:
            stack.enter_context(patch.dict(sys.modules, deps))
            for name in names:
                stack.enter_context(patch.object(services, name, deps[f'services.{name}'], create=True))
            pipeline = load_source('_test_preflight', 'run_pipeline.py', deps)
            pipeline._ensure_logs = lambda: None
            pipeline._log = lambda message: None
            with self.assertRaisesRegex(RuntimeError, 'empty KB'):
                pipeline.run_full_pipeline()
        deps['services.faq_generator'].main.assert_not_called()
        retriever.main.assert_not_called()

    def setUp(self):
        self.docs = [dict(chunk_id=f'faq:{i}', page_label=None, text=f'KB evidence {i}',
                          rerank_score=.9 - i * .1) for i in range(5)]
        self.record = dict(clusterLabel=1, query='Question', retrieved=self.docs,
                           candidateType='EXPAND', qType=4, category='Category',
                           similarQuestions=['User question'],
                           matchedFaqs=[dict(matchedFaqSeqNum=123, matchScore=.9)])
        self.candidates = [dict(clusterLabel=1, tag='FAQ', standardQuestion='Question')]

    def test_binding_preserves_metadata_without_mutating_input(self):
        rows = bind_retrieval(self.candidates, index_retrieval({'faqRetrieval': [self.record]}))
        self.assertEqual(rows[0]['matchedFaqs'], self.record['matchedFaqs'])
        rows[0]['matchedFaqs'].clear()
        self.assertEqual(len(self.record['matchedFaqs']), 1)
        self.assertNotIn('matchedFaqs', self.candidates[0])

    def test_missing_stale_extra_and_duplicate_results_fail(self):
        for records in ([], [{**self.record, 'query': 'Old question'}],
                        [self.record, {**self.record, 'clusterLabel': 2}],
                        [self.record, self.record]):
            with self.subTest(records=records), self.assertRaises(ValueError):
                bind_retrieval(self.candidates, index_retrieval({'faqRetrieval': records}))

    def test_empty_search_and_non_faq_are_valid(self):
        rows = self.candidates + [dict(clusterLabel=2, tag='EMERGENCY')]
        retrieval = index_retrieval({'faqRetrieval': [{**self.record, 'retrieved': []}]})
        self.assertEqual(len(bind_retrieval(rows, retrieval)), 2)
        self.assertEqual(select_evidence(retrieval[1], .05, 5), [])

    def test_invalid_scores_and_text_fail(self):
        for field, value in [('rerank_score', float('nan')), ('rerank_score', 2),
                             ('rerank_score', True), ('text', '')]:
            record = copy.deepcopy(self.record)
            record['retrieved'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                index_retrieval({'faqRetrieval': [record]})

    def test_top_five_and_threshold(self):
        self.assertEqual(len(select_evidence(self.record, .05, 5)), 5)
        selected = select_evidence(self.record, .75, 5)
        self.assertEqual([d['chunk_id'] for d in selected], ['faq:0', 'faq:1'])

    def test_real_prompt_builder_passes_all_five_documents(self):
        torch = types.ModuleType('torch')
        torch.cuda = types.SimpleNamespace(is_available=lambda: False)
        torch.bfloat16 = 'bfloat16'
        torch.no_grad = lambda: (lambda function: function)
        transformers = types.ModuleType('transformers')
        transformers.AutoTokenizer = transformers.AutoModelForCausalLM = object
        ar = load_source('_test_answer_regenerator', 'answer_regenerator.py',
                         {'torch': torch, 'transformers': transformers})
        ar.templates_meta = dict(formality_levels={'formal': 'Formal'}, persona='Assistant')
        cfg = dict(formality='formal', citation_style='none', default_contacts=[],
                   description='FAQ', body_guideline='Use evidence', prefix='Hello', suffix='Thanks')
        prompt = ar.build_prompt(self.candidates[0], cfg, {'citation_phrases': {}},
                                 ar.select_kb_chunks(self.record))
        for doc in self.docs:
            self.assertIn(doc['text'], prompt)
            self.assertIn(doc['chunk_id'], prompt)

    def test_pipeline_keeps_evidence_and_match_fields_through_refine(self):
        ar = types.ModuleType('services.answer_regenerator')
        ar.load_candidates = lambda: copy.deepcopy(self.candidates)
        ar.load_retrieval = lambda: {1: copy.deepcopy(self.record)}
        ar.load_templates = lambda: {'_meta': {}}
        ar.select_kb_chunks = lambda record: select_evidence(record, .05, 5)
        ar.KB_SCORE_THRESHOLD, ar.MAX_KB_CHUNKS = .05, 5
        ae = types.ModuleType('services.answer_evaluator')
        ae.DEVICE = 'fake'
        ae.load_llm = lambda: (None, None)
        ae.load_ce = lambda: None
        deps = {'services.answer_regenerator': ar, 'services.answer_evaluator': ae,
                'services.answer_refiner': types.ModuleType('services.answer_refiner'),
                'services.oos_refresher': types.ModuleType('services.oos_refresher'),
                'numpy': types.SimpleNamespace(mean=lambda values: sum(values) / len(values)),
                'pandas': types.SimpleNamespace(read_csv=lambda *args, **kwargs: None)}
        pipeline = load_source('_test_pipeline', 'run_pipeline.py', deps)
        pipeline._ensure_logs = lambda: None
        pipeline._log = lambda message: None
        pipeline.stage_regenerate = Mock(return_value=('Initial answer', [], 1))
        pipeline.stage_refine = Mock(return_value=('Revised question', 'Final answer', True, 1))
        pipeline.stage_oos_case = Mock(return_value=None)
        pipeline._evaluate = Mock(side_effect=[{'needs_review_v2': True, 'llm_mean': 3},
                                               {'needs_review_v2': False, 'llm_mean': 4}])
        with tempfile.TemporaryDirectory() as directory:
            pipeline.OUTPUT_JSON = Path(directory) / 'final.json'
            pipeline.run_answer_pipeline()
            result = json.loads(pipeline.OUTPUT_JSON.read_text(encoding='utf-8'))['faqCandidates'][0]
        self.assertEqual(result['answerDraft'], 'Final answer')
        self.assertEqual(result['matchedFaqs'], self.record['matchedFaqs'])
        self.assertEqual(result['retrievalEvidence']['query'], 'Question')
        self.assertEqual(len(result['retrievalEvidence']['used']), 5)
        self.assertTrue(result['retrievalEvidence']['questionChangedAfterRetrieval'])


if __name__ == '__main__':
    unittest.main()
