import os
import sys
import unittest
from contextlib import nullcontext
from types import SimpleNamespace
from unittest.mock import patch

from services.llm_runtime import DEFAULT_MODEL, model_id, stop_tokens, generate_chat


class ChatRuntimeTest(unittest.TestCase):
    def test_judge_receives_used_retrieval_evidence(self):
        import importlib.util
        from pathlib import Path
        # Load under a private name: the existing pipeline tests replace service imports.
        spec = importlib.util.spec_from_file_location(
            'judge_prompt_under_test', Path(__file__).parents[1] / 'services/answer_evaluator.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        prompt = module.build_judge_prompt(dict(tag='FAQ', standardQuestion='질문', answerDraft='답변',
                                               retrievalEvidence={'used': [{'text': '검증용 검색 근거'}]}))
        self.assertIn('[근거 1] 검증용 검색 근거', prompt)

    def test_synonym_objects_are_not_parsed_as_faq_candidates(self):
        from services.faq_generator import parse_response
        self.assertIsNone(parse_response('FAQ markdown: {"text":"가방","type":"SYNONYM"}'))

    def test_malformed_faq_is_retried_then_fails_closed(self):
        from services import faq_generator as FG
        with patch.object(FG, 'generate', return_value='not JSON') as generate:
            obj, trials = FG.generate_candidate('prompt', None, None)
        self.assertEqual(generate.call_count, 2)
        self.assertEqual(len(trials), 2)
        self.assertEqual(obj['tag'], 'REJECT')

    def test_corrected_faq_is_accepted_on_second_attempt(self):
        from services import faq_generator as FG
        raw = '{"tag":"FAQ","standardQuestion":"문의 방법은?","answerDraft":"역 직원에게 문의하세요.","synonyms":[],"reason":"역 안내"}'
        with patch.object(FG, 'generate', side_effect=['not JSON', raw]):
            obj, trials = FG.generate_candidate('prompt', None, None)
        self.assertEqual(obj['tag'], 'FAQ')
        self.assertEqual(len(trials), 2)

    def test_offloaded_embedding_uses_hook_device_instead_of_meta_tensor(self):
        class Batch(dict):
            def to(self, device):
                self.device = device
                return self
        batch = Batch(input_ids=SimpleNamespace(shape=(1, 2)))
        tok = SimpleNamespace(apply_chat_template=lambda *a, **kw: batch,
                              eos_token_id=2, pad_token_id=None,
                              get_vocab=lambda: {}, decode=lambda *a, **kw: 'answer')
        model = SimpleNamespace(config=SimpleNamespace(max_position_embeddings=4096),
                                generation_config=SimpleNamespace(eos_token_id=2),
                                get_input_embeddings=lambda: SimpleNamespace(
                                    weight=SimpleNamespace(device='meta'),
                                    _hf_hook=SimpleNamespace(execution_device=0)),
                                generate=lambda **kw: [[0, 1, 2]])
        with patch.dict(sys.modules, {'torch': SimpleNamespace(inference_mode=nullcontext)}):
            result = generate_chat([], tok, model, max_new_tokens=5, temperature=0, top_p=1)
        self.assertEqual(batch.device, 0)
        self.assertEqual(result, 'answer')

    def test_independent_judge_does_not_generate_answers(self):
        from services.llm_runtime import load_pipeline_models
        with patch.dict(os.environ, {'TTOBAGI_LLM_MODEL': 'generator',
                                    'TTOBAGI_JUDGE_MODEL': 'judge'}):
            self.assertEqual(load_pipeline_models(lambda: ('gt', 'gm'), lambda: ('jt', 'jm')),
                             ('gt', 'gm', 'jt', 'jm'))

    def test_defaults_and_explicit_rollback(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(model_id(), DEFAULT_MODEL)
            self.assertEqual(model_id('judge'), DEFAULT_MODEL)
        with patch.dict(os.environ, {'TTOBAGI_LLM_MODEL': 'backup/local-model',
                                    'TTOBAGI_JUDGE_MODEL': 'independent/judge'}):
            self.assertEqual(model_id(), 'backup/local-model')
            self.assertEqual(model_id('judge'), 'independent/judge')

    def test_end_of_turn_and_generation_config_stops_are_preserved(self):
        tok = SimpleNamespace(eos_token_id=128001,
                              get_vocab=lambda: {'<|eot_id|>': 128009},
                              convert_tokens_to_ids=lambda _: 128009)
        mdl = SimpleNamespace(generation_config=SimpleNamespace(eos_token_id=[128001, 128008]))
        self.assertEqual(stop_tokens(tok, mdl), [128001, 128008, 128009])

    def test_context_overflow_fails_before_inference(self):
        tok = SimpleNamespace(apply_chat_template=lambda *a, **kw:
                              {'input_ids': SimpleNamespace(shape=(1, 100))})
        mdl = SimpleNamespace(config=SimpleNamespace(max_position_embeddings=110))
        with patch.dict(sys.modules, {'torch': SimpleNamespace()}):
            with self.assertRaisesRegex(ValueError, 'context budget exceeded'):
                generate_chat([], tok, mdl, max_new_tokens=20, temperature=0, top_p=1)

    def test_faq_field_type_failure_does_not_escape_to_backend(self):
        from services.faq_generator import validate
        result = validate({'tag': 'FAQ', 'answerDraft': [{'text': 'invalid'}]})
        self.assertEqual(result['tag'], 'REJECT')
        self.assertEqual(result['answerDraft'], '')
        self.assertEqual(result['synonyms'], [])

    def test_faq_json_matches_existing_backend_field_types(self):
        from services.faq_generator import validate, parse_response
        raw = '{"tag":"FAQ","standardQuestion":"문의 방법은?","answerDraft":"역 직원에게 문의하세요.","synonyms":[],"reason":"역 안내"}'
        obj = validate(parse_response(raw))
        self.assertEqual(obj['tag'], 'FAQ')
        self.assertIsInstance(obj['answerDraft'], str)


if __name__ == '__main__':
    unittest.main()
