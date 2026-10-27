"""English policy and real CPU extraction/dashboard integration; no GPU mocks."""
import copy
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from sklearn.feature_extraction.text import CountVectorizer, TfidfTransformer

from services.keyword_policy import KeywordPolicy, DEFAULT_POLICY_PATH, STOPWORDS
from services.clustering import extract_keywords
from services.dashboard_metrics import _burst_keywords
from services.run_job import run_job


class KeywordPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policy = KeywordPolicy.load(DEFAULT_POLICY_PATH)
        self.definition = json.loads(self.policy.source_bytes)

    def custom_policy(self, definition):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'policy.json'
            path.write_text(json.dumps(definition), encoding='utf-8')
            return KeywordPolicy.load(path)

    def test_fixed_acceptance_samples_and_repeatability(self):
        cases = json.loads((Path(__file__).parent / 'fixtures/keyword_policy_cases.json').read_text(encoding='utf-8'))
        for case in cases:
            with self.subTest(text=case['text']):
                for _ in range(2):
                    self.assertEqual(self.policy.english_tokens(case['text']), (case['english'], case['excluded']))
        self.assertEqual(self.policy.sha256, hashlib.sha256(DEFAULT_POLICY_PATH.read_bytes()).hexdigest())
        self.assertIn('wifi', self.policy.tokenize('WiFi가 안 돼요')[0])
        self.assertEqual(self.policy.tokenize('시부터 심해서')[0], ['시부터', '심해서'])

    def test_protected_phrase_priority_longest_match_and_canonical_idempotence(self):
        self.definition['protected_phrases'].update({'The Who': 'the who', 'The Who Live': 'the who live'})
        self.definition['protected_tokens'].append('is')
        policy = self.custom_policy(self.definition)
        self.assertEqual(policy.english_tokens('THE WHO LIVE The Who is the'),
                         (['the who live', 'the who', 'is'], 1))
        for word in ('Wi-Fi', 'wi fi', 'Seoul Station', 'T-money', 'The Who'):
            self.assertEqual(policy.candidate(policy.candidate(word)), policy.candidate(word))
        self.assertIsNone(policy.candidate('the'))

    def test_invalid_policies_fail_without_including_contents(self):
        bad_definitions = []
        for field, value in [('version', ''), ('stopwords', 'the'), ('protected_tokens', [None]),
                             ('aliases', {'x': ''}), ('aliases', {'x': 'y', 'y': 'x'}),
                             ('aliases', {'WiFi': 'wifi', 'ＷｉＦｉ': 'refund'}),
                             ('protected_phrases', [])]:
            definition = copy.deepcopy(self.definition)
            definition[field] = value
            bad_definitions.append(definition)
        for definition in bad_definitions:
            with self.subTest(definition=definition), self.assertRaises(ValueError):
                self.custom_policy(definition)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'policy.json'
            for contents in ('{bad secret sentence', '{"version":"v1","version":"v2"}', 'null'):
                path.write_text(contents)
                with self.assertRaises(ValueError) as error:
                    KeywordPolicy.load(path)
                self.assertNotIn('secret sentence', str(error.exception))
            with self.assertRaises(ValueError):
                KeywordPolicy.load(Path(directory) / 'missing')

    def test_real_extraction_empty_partial_noise_and_audit(self):
        texts = ['the is please', 'THE WiFi Wi-Fi wi fi password', 'a an']
        labels = np.array([0, 1, -1])
        with tempfile.TemporaryDirectory() as directory:
            audit_path = Path(directory) / 'audit.json'
            keywords, docs = extract_keywords(texts, labels, policy=self.policy, audit_path=audit_path)
            self.assertEqual(keywords[0], [])
            self.assertEqual(keywords[-1], [])
            self.assertEqual(keywords[1][0], 'wifi')
            self.assertIn('password', keywords[1])
            self.assertEqual(docs, {0: [texts[0]], 1: [texts[1]], -1: [texts[2]]})
            audit = json.loads(audit_path.read_text())
            self.assertEqual(audit, {'policyVersion': 'en-v1', 'policySha256': self.policy.sha256,
                                    'excludedEnglishTokenOccurrences': 6, 'emptyKeywordClusterCount': 2})
        for texts, labels, expected in [([], [], {}), (['the is'], [-1], {-1: []})]:
            self.assertEqual(extract_keywords(texts, np.array(labels), policy=self.policy)[0], expected)
        with self.assertRaises(ValueError):
            extract_keywords(['wifi'], np.array([]), policy=self.policy)

    def test_log_boundary_not_crossed_and_counts_kept_for_scoring(self):
        keywords, _ = extract_keywords(['wi', 'fi'], np.array([0, 0]), policy=self.policy)
        self.assertEqual(set(keywords[0]), {'wi', 'fi'})
        captured = {}
        def vectorizer(**options):
            instance = CountVectorizer(**options)
            original = instance.fit_transform
            def fit(documents):
                matrix = original(documents)
                captured.update(zip(instance.get_feature_names_out(), matrix.toarray()[0]))
                return matrix
            instance.fit_transform = fit
            return instance
        with patch('services.clustering.CountVectorizer', side_effect=vectorizer):
            extract_keywords(['WiFi Wi-Fi wi fi', 'WiFi'], np.array([0, 0]), policy=self.policy)
        self.assertEqual(captured, {'wifi': 4})

    def test_korean_regression_matches_previous_score_and_top_seven(self):
        texts = ['환불 환불 지연 안내 문의', '환불 분실물 접수', '시부터 심해서 혼잡 문의', '요금 교통카드']
        labels = np.array([0, 0, 1, -1])
        cluster_ids = [-1, 0, 1]
        documents = [' '.join(t for t, label in zip(texts, labels) if label == cid) for cid in cluster_ids]
        def previous_tokenizer(text):
            return [word for word in re.findall(r'\[[A-Z_]+\]|[가-힣]{2,}|[a-zA-Z]{2,}', text) if word not in STOPWORDS]
        cv = CountVectorizer(tokenizer=previous_tokenizer, token_pattern=None)
        scores = TfidfTransformer(smooth_idf=True, sublinear_tf=True).fit_transform(cv.fit_transform(documents))
        vocabulary = cv.get_feature_names_out()
        expected = {}
        for index, cid in enumerate(cluster_ids):
            row = scores[index].toarray().flatten()
            expected[cid] = [vocabulary[j] for j in row.argsort()[::-1][:7] if row[j] > 0]
        self.assertEqual(extract_keywords(texts, labels, policy=self.policy)[0], expected)
        self.assertEqual(texts[0], '환불 환불 지연 안내 문의')

    def test_real_bursts_filter_aliases_boundaries_and_preserve_period_contract(self):
        dates = {1: date(2026, 10, 1), **{i: date(2026, 10, 8) for i in range(2, 5)}}
        points = [{'sourceLogSeqNum': i, 'clusterLabel': 0,
                   'text': 'theater training wifi6 my_wifi' if i == 1 else 'THE WiFi Wi-Fi wi fi not 환불요청'} for i in dates]
        keywords = {'0': ['the', 'please', 'he', 'train', 'wifi', 'Wi-Fi', 'not', '환불'], '1': ['wi fi']}
        bursts, surges = _burst_keywords(points, dates, keywords, dates.values(), policy=self.policy)
        self.assertEqual({item['keyword'] for item in bursts}, {'wifi', 'not', '환불'})
        for burst, surge in zip(bursts, surges):
            self.assertEqual(burst['count'], 3)
            self.assertTrue(surge['isNew'])
            self.assertIsNone(burst['increasedRate'])
            self.assertEqual(len(surge['periodStats']), 14)
            self.assertEqual(surge['relatedClusterLabel'], 0)
        self.assertEqual(_burst_keywords(points, dates, {'0': ['the']}, dates.values(), policy=self.policy), ([], []))
        self.assertEqual(_burst_keywords(points, dates, {}, [], policy=self.policy), ([], []))

    def test_protected_phrase_does_not_double_count_internal_word(self):
        self.definition['protected_phrases']['The Who'] = 'the who'
        policy = self.custom_policy(self.definition)
        dates = {1: date(2026, 10, 1), **{i: date(2026, 10, 8) for i in range(2, 5)}}
        points = [{'sourceLogSeqNum': i, 'clusterLabel': -1, 'text': 'The Who'} for i in dates]
        bursts, _ = _burst_keywords(points, dates, {'-1': ['the who', 'who', 'the']}, dates.values(), policy=policy)
        self.assertEqual([b['keyword'] for b in bursts], ['the who'])

    def test_common_module_does_not_import_inference_dependencies(self):
        root = str(Path(__file__).resolve().parents[1])
        code = f"import sys; sys.path.insert(0, {root!r}); from services.keyword_policy import get_policy; get_policy(); assert not {{'torch','numpy','pandas','sklearn','transformers'}} & set(sys.modules)"
        subprocess.run([sys.executable, '-c', code], cwd=Path(__file__).parents[1], check=True)

    def test_job_policy_failure_happens_before_subprocess_and_marks_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.csv'
            source.write_text('private input sentence')
            with patch('services.run_job.DEFAULT_POLICY_PATH', root / 'missing.json'), patch('services.run_job.subprocess.run') as process:
                with self.assertRaises(ValueError):
                    run_job(source, root / 'job', templates=source)
            process.assert_not_called()
            status = (root / 'job/status.json').read_text()
            self.assertEqual(json.loads(status)['status'], 'FAILED')
            self.assertNotIn('private input sentence', status)

    def test_job_snapshots_bytes_before_inference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.csv'
            source.write_text('input')
            def inference(command, **options):
                snapshot = Path(options['env']['TTOBAGI_KEYWORD_POLICY_PATH'])
                self.assertEqual(snapshot.read_bytes(), self.policy.source_bytes)
                self.assertNotEqual(snapshot.parent, DEFAULT_POLICY_PATH.parent)
                (Path(options['env']['TTOBAGI_DATA_DIR']) / 'faq_candidates_final.json').write_text('{"faqCandidates": []}')
                return subprocess.CompletedProcess(command, 0)
            with patch('services.run_job.subprocess.run', side_effect=inference):
                self.assertEqual(run_job(source, root / 'job', templates=source), 0)

    def test_actual_worker_records_failed_callback_for_invalid_policy(self):
        from services.job_store import JobStore
        from services.pipeline_worker import process_one
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input.csv'
            source.write_text('private input sentence')
            store = JobStore(root)
            store.submit(1, 'input', {'masking': True, 'translation': False}, source)
            with patch('services.run_job.DEFAULT_POLICY_PATH', root / 'missing.json'), patch('services.run_job.subprocess.run') as process:
                self.assertTrue(process_one(store))
            process.assert_not_called()
            payload = json.loads(store.get(1)['payload'])
            self.assertEqual(payload['status'], 'FAILED')
            self.assertNotIn('private input sentence', json.dumps(payload))


if __name__ == '__main__':
    unittest.main()
