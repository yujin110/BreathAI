"""Selection, real no-model pipeline, v2 callback and progress race boundaries."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from services.analysis_input import read_analysis_csv
from services.analysis_selection import (prepare_selection, finalize_selection, write_json,
                                         write_csv, empty_clusters, PREPARED_COLUMNS)
from services.callback_payload import build_callback
from services.job_store import JobStore
from services.keyword_policy import get_policy
from services.pipeline_api import create_app
from tests.test_pipeline_api import EndpointClient


class SelectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def source(self, labels):
        write_csv(self.root / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                  [{'번호': i, '질문': '환승 문의', '날짜': '2026-11-05', 'logType': label}
                   for i, label in enumerate(labels, 1)])

    def prepared(self, targets, text='환승 문의'):
        write_csv(self.root / 'preprocessed_full.csv', PREPARED_COLUMNS,
                  [dict.fromkeys(PREPARED_COLUMNS, '') | {'logId': r['번호'], 'logType': r['logType'],
                     'normalizedForClustering': text} for r in targets])

    def finish(self):
        return finalize_selection(self.root, min_cluster_size=5, min_samples=2, faq_min_size=5)

    def test_modes_select_both_outcomes_and_wrong_answer_only(self):
        for labels in [['CORRECT', 'LOW_QUALITY', 'UNANSWER'], ['LOW_QUALITY'] * 5]:
            self.source(labels)
            for mode in ['IMPROVEMENT', 'UNANSWER_ONLY', 'ALL']:
                targets = prepare_selection(self.root, mode)
                expected = [str(i) for i, label in enumerate(labels, 1) if mode == 'ALL' or
                            label == 'UNANSWER' or (mode == 'IMPROVEMENT' and label == 'LOW_QUALITY')]
                self.assertEqual([r['번호'] for r in targets], expected)
                manifest = [json.loads(line) for line in (self.root / 'classification_manifest.jsonl').read_text(encoding='utf-8').splitlines()]
                self.assertEqual(len(manifest), len(labels))
                self.assertEqual(sum(row['isTarget'] for row in manifest), len(expected))

    def test_no_targets_empty_text_and_small_input_are_valid_callbacks(self):
        cases = [(['CORRECT'] * 10, '환승', 'NO_TARGET_LOGS'),
                 (['LOW_QUALITY'] * 5, '', 'NO_ELIGIBLE_TEXT'),
                 (['UNANSWER'] * 2, '환승', 'INSUFFICIENT_TARGET_LOGS')]
        for labels, text, reason in cases:
            with self.subTest(reason=reason):
                self.source(labels)
                targets = prepare_selection(self.root, 'IMPROVEMENT')
                self.prepared(targets, text)
                empty_clusters(self.root, get_policy())
                result = self.finish()
                self.assertEqual(result['completionReason'], reason)
                write_json(self.root / 'faq_candidates_final.json', {'faqCandidates': []})
                from services.dashboard_metrics import build_dashboard
                dashboard = build_dashboard(self.root, {}, {})
                self.assertEqual(dashboard['systemStatus']['totalLogCount'], len(labels))
                self.assertEqual(dashboard['points'], [])

    def test_noise_scope_and_tampered_cluster_coverage(self):
        self.source(['CORRECT'] * 3 + ['LOW_QUALITY'] * 5)
        targets = prepare_selection(self.root, 'IMPROVEMENT')
        self.prepared(targets)
        empty_clusters(self.root, get_policy())
        write_csv(self.root / 'clusters.csv', ['logId', 'clusterId', 'umap_x', 'umap_y', 'text'],
                  [dict(logId=r['번호'], clusterId=-1, umap_x=1, umap_y=2, text='환승 문의') for r in targets])
        write_csv(self.root / 'cluster_summary.csv', ['clusterId', 'size', 'isNoise', 'topKeywords'],
                  [dict(clusterId=-1, size=5, isNoise=True, topKeywords='')])
        result = self.finish()
        self.assertEqual(result['completionReason'], 'NO_FAQ_CLUSTERS')
        self.assertEqual(result['analysisScope']['noiseLogCount'], 5)
        # A CORRECT ID replacing a selected ID must fail even if counts still match.
        content = (self.root / 'clusters.csv').read_text(encoding='utf-8-sig')
        (self.root / 'clusters.csv').write_text(content.replace('4,-1', '1,-1'), encoding='utf-8-sig')
        with self.assertRaises(ValueError):
            self.finish()

    def test_completed_callback_matches_shared_fixture(self):
        fixture = json.loads((Path(__file__).parent / 'fixtures/analysis_v2/01_completed.json').read_text(encoding='utf-8'))
        job = self.root / 'job'
        data = job / 'dataset'
        data.mkdir(parents=True)
        (job / 'keyword_policy.json').write_bytes(get_policy().source_bytes)
        points = fixture['clusteringView']['points']
        labels = ['CORRECT'] * 5 + [point['logType'] for point in points]
        write_csv(data / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                  [dict(번호=i, 질문='환승 문의', 날짜='2026-11-05', logType=label) for i, label in enumerate(labels, 1)])
        prepare_selection(data, 'IMPROVEMENT')
        write_csv(data / 'preprocessed_full.csv', PREPARED_COLUMNS,
                  [dict.fromkeys(PREPARED_COLUMNS, '') | dict(logId=p['sourceLogSeqNum'], logType=p['logType'],
                                                            normalizedForClustering=p['text']) for p in points])
        empty_clusters(data, get_policy())
        write_csv(data / 'clusters.csv', ['logId', 'clusterId', 'umap_x', 'umap_y', 'text'],
                  [dict(logId=p['sourceLogSeqNum'], clusterId=p['clusterLabel'], umap_x=p['x'], umap_y=p['y'],
                        text=p['text']) for p in points])
        write_csv(data / 'cluster_summary.csv', ['clusterId', 'size', 'isNoise', 'topKeywords'],
                  [dict(clusterId=0, size=5, isNoise=False, topKeywords='분실물')])
        from services.keyword_policy import write_audit
        words = {0: ['분실물', '문의']}
        write_json(data / 'cluster_keywords.json', {'0': words[0]})
        write_audit(data / 'keyword_policy_audit.json', get_policy(), 0, words)
        finalize_selection(data, min_cluster_size=5, min_samples=2, faq_min_size=5)
        write_json(data / 'faq_candidates_final.json', {'faqCandidates': fixture['faqCandidates']})
        result = build_callback(job)
        for key in ('resultSchemaVersion', 'analysisMode', 'completionReason', 'analysisScope',
                    'systemStatus', 'performanceMetrics', 'usageStats', 'trend', 'faqCandidates'):
            self.assertEqual(result[key], fixture[key], key)
        self.assertEqual(result['clusteringView']['points'], fixture['clusteringView']['points'])
        # Cluster display names are free text, not fixed strings from the sample.
        for actual, expected in zip(result['clusteringView']['clusterNames'], fixture['clusteringView']['clusterNames']):
            for key in ('clusterLabel', 'topKeywords', 'size'):
                self.assertEqual(actual[key], expected[key])
            self.assertTrue(actual['name'])

    def test_alias_direct_upload_and_precedence(self):
        path = self.root / 'alias.csv'
        path.write_text('番号,質問\n', encoding='utf-8')
        for suffix, value in [('', '24%'), (',ai_percent', '24%,8000')]:
            path.write_text('번호,질문,날짜,답변확률' + suffix + '\n1,환승,2026-11-05,' + value + '\n', encoding='utf-8')
            fields, rows = read_analysis_csv(path)
            from services.log_classification import log_type
            self.assertEqual(log_type(rows[0]), 'CORRECT' if suffix else 'UNANSWER')

    def test_19000_row_selection_preserves_both_failure_types(self):
        self.source(['CORRECT'] * 17100 + ['LOW_QUALITY'] * 950 + ['UNANSWER'] * 950)
        targets = prepare_selection(self.root, 'IMPROVEMENT')
        self.assertEqual(len(targets), 1900)
        self.assertEqual(sum(row['logType'] == 'LOW_QUALITY' for row in targets), 950)
        self.assertEqual(sum(row['logType'] == 'UNANSWER' for row in targets), 950)

    def test_real_full_pipeline_no_target_does_not_import_inference(self):
        import services.run_pipeline as pipeline
        job = self.root / 'job'
        data = job / 'dataset'
        data.mkdir(parents=True)
        (job / 'keyword_policy.json').write_bytes(get_policy().source_bytes)
        write_csv(data / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                  [{'번호': 1, '질문': '환승', '날짜': '2026-11-05', 'logType': 'CORRECT'}])
        imports = []
        original = __import__
        def guarded(name, *args, **kwargs):
            imports.append(name)
            if name in ('torch', 'services.faq_generator', 'services.retriever', 'services.answer_regenerator'):
                raise AssertionError('Unexpected inference import')
            return original(name, *args, **kwargs)
        with patch.multiple(pipeline, DATA_DIR=data, LOG_DIR=job / 'logs',
                            RUN_LOG=job / 'logs/run_pipeline.log', OUTPUT_JSON=data / 'faq_candidates_final.json'), \
                patch.dict(os.environ, {'TTOBAGI_ANALYSIS_MODE': 'IMPROVEMENT'}), \
                patch('builtins.__import__', side_effect=guarded):
            pipeline.run_full_pipeline()
        callback = build_callback(job)
        self.assertEqual(callback['completionReason'], 'NO_TARGET_LOGS')
        self.assertEqual(callback['systemStatus']['totalLogCount'], 1)
        self.assertEqual(callback['analysisScope']['targetLogCount'], 0)
        saved = json.loads((data / 'analysis_scope.json').read_text(encoding='utf-8'))
        saved['analysisScope']['totalLogCount'] = 2
        write_json(data / 'analysis_scope.json', saved)
        with self.assertRaises(ValueError):
            build_callback(job)


class ProgressTest(unittest.TestCase):
    def test_real_worker_empty_result_records_final_progress(self):
        from services.pipeline_worker import process_claimed
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            uploads = root / 'uploads'
            uploads.mkdir()
            source = uploads / 'normal.csv'
            write_csv(source, ['번호', '질문', '날짜', 'logType'],
                      [dict(번호=1, 질문='환승', 날짜='2026-11-05', logType='CORRECT')])
            store = JobStore(root)
            store.submit(1, 'same', dict(masking=True, translation=False, analysisMode='IMPROVEMENT'), source)
            states = []
            process_claimed(store, store.claim(), on_progress=lambda job, status: states.append(status))
            row = store.get(1)
            self.assertEqual(row['state'], 'COMPLETED')
            self.assertEqual(json.loads(row['progress'])['phase'], 'FINALIZING')
            self.assertEqual(json.loads(row['progress'])['completedUnits'], 1)
            self.assertEqual(json.loads(row['payload'])['completionReason'], 'NO_TARGET_LOGS')
            self.assertEqual(states, ['PREPROCESSING'])
            metrics = json.loads((root / 'analysis-1/performance.json').read_text(encoding='utf-8'))
            self.assertGreaterEqual(metrics['queueSeconds'], 0)

    def test_sequence_terminal_freeze_and_phase_regression(self):
        with tempfile.TemporaryDirectory() as directory:
            store = JobStore(directory)
            source = Path(directory) / 'source.csv'
            store.submit(1, 'same', {'analysisMode': 'IMPROVEMENT'}, source)
            queued = json.loads(store.get(1)['progress'])
            self.assertEqual(queued['phase'], 'QUEUED')
            self.assertEqual(queued['elapsedSeconds'], 0)
            store.claim()
            event = dict(phase='EMBEDDING', completedUnits=32, totalUnits=100, unit='LOG')
            store.update_progress(1, event)
            first = json.loads(store.get(1)['progress'])
            store.update_progress(1, {**event, 'phase': 'PREPROCESSING'})
            second = json.loads(store.get(1)['progress'])
            self.assertEqual(second['phase'], 'EMBEDDING')
            self.assertGreater(second['sequence'], first['sequence'])
            store.finish(1, {'status': 'FAILED'})
            terminal = store.get(1)['progress']
            store.update_progress(1, {**event, 'phase': 'FINALIZING'})
            store.heartbeat()
            self.assertEqual(store.get(1)['progress'], terminal)

    def test_cancel_freezes_progress_and_legacy_returns_null(self):
        with tempfile.TemporaryDirectory() as directory:
            store = JobStore(directory)
            store.submit(1, 'same', {}, Path(directory) / 'source.csv')
            store.heartbeat()
            self.assertIsNone(store.get(1)['progress'])
            store.submit(2, 'new', {'analysisMode': 'ALL'}, Path(directory) / 'other.csv')
            store.request_cancel(2)
            store.complete_cancel(2)
            terminal = store.get(2)['progress']
            store.heartbeat()
            self.assertEqual(store.get(2)['progress'], terminal)


class ModeApiTest(unittest.TestCase):
    @unittest.skipUnless(os.getenv('TTOBAGI_TEST_HTTP') == '1', 'Requires local async/socket runtime')
    def test_real_multipart_rejects_explicit_blank_and_defaults_only_when_omitted(self):
        from fastapi.testclient import TestClient
        with tempfile.TemporaryDirectory() as directory:
            app = create_app(root=directory, token='test')
            with TestClient(app) as client:
                for index, mode in enumerate(['', 'improvement', 'INVALID', None, 'UNANSWER_ONLY'], 1):
                    data = {'analysisId': str(index), 'uploadId': 'x'}
                    if mode is not None:
                        data['analysisMode'] = mode
                    response = client.post('/pipeline', headers={'Authorization': 'Bearer test'}, data=data,
                                           files={'file': ('input.csv', '번호,질문,날짜\n1,환승,2026-11-05\n'.encode())})
                    self.assertEqual(response.status_code, 422 if index < 4 else 202)
                self.assertEqual(json.loads(app.state.store.get(4)['metadata'])['analysisMode'], 'IMPROVEMENT')

    def test_mode_validation_fingerprint_and_worker_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            app = create_app(root=directory, token='test')
            client = EndpointClient(app)
            def submit(mode='IMPROVEMENT'):
                return client.post('/pipeline', headers={'Authorization': 'Bearer test'},
                                   data={'analysisId': '1', 'uploadId': 'x', 'analysisMode': mode},
                                   files={'file': ('x.csv', '번호,질문,날짜\n1,환승,2026-11-05\n'.encode())})
            for mode in ['', 'improvement', 'INVALID']:
                self.assertEqual(submit(mode).status_code, 422)
            self.assertEqual(submit().status_code, 202)
            self.assertEqual(submit().status_code, 202)
            self.assertEqual(submit('UNANSWER_ONLY').status_code, 409)
            value = json.loads(app.state.store.get(1)['metadata'])
            self.assertEqual(value['analysisMode'], 'IMPROVEMENT')
            self.assertEqual(client.get('/jobs/1', headers={'Authorization': 'Bearer test'}).json()['progress']['phase'], 'QUEUED')


class SmallPipelineTest(unittest.TestCase):
    def test_two_noise_questions_are_eligible_for_faq_generation(self):
        import numpy as np
        import sys
        from types import SimpleNamespace
        from services import clustering, faq_generator
        from services.analysis_selection import prepare_selection, finalize_selection
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            texts = ['지하철 라이트 꺼짐', '지하철 전등이 안 켜져요']
            write_csv(data / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                      [{'번호': i, '질문': text, '날짜': '2026-11-05', 'logType': 'UNANSWER'}
                       for i, text in enumerate(texts, 1)])
            prepare_selection(data, 'IMPROVEMENT')
            write_csv(data / 'preprocessed_full.csv', ['logId', 'normalizedForClustering', 'logType'],
                      [{'logId': i, 'normalizedForClustering': text, 'logType': 'UNANSWER'}
                       for i, text in enumerate(texts, 1)])
            with patch.dict(sys.modules, {'torch': SimpleNamespace(cuda=SimpleNamespace(is_available=lambda: False))}), \
                 patch.multiple(clustering, DATA_DIR=data, INPUT_CSV=data / 'preprocessed_full.csv'), \
                 patch.object(clustering, 'load_model', return_value=(None, None)), \
                 patch.object(clustering, 'encode', return_value=np.ones((2, 2))), \
                 patch.object(clustering, 'plot_2d', side_effect=lambda *args: args[-1].write_bytes(b'plot')):
                clustering.main(route=lambda _: ([{'status': 'UNMATCHED'} for _ in texts], {},
                                                 lambda *_: {'decision': 'same'}, 'fixture'))
            scope = finalize_selection(data, min_cluster_size=5, min_samples=2, faq_min_size=5)
            self.assertEqual('RESULTS_GENERATED', scope['completionReason'])
            self.assertEqual(0, scope['analysisScope']['noiseLogCount'])
            with patch.multiple(faq_generator, DATA_DIR=data, CLUSTERS_CSV=data / 'clusters.csv',
                                SUMMARY_CSV=data / 'cluster_summary.csv', KEYWORDS_JSON=data / 'cluster_keywords.json'):
                cluster, = faq_generator.load_clusters()
            self.assertEqual([1, 2], cluster['logIds'])

    def test_single_confirmed_faq_keeps_cluster_and_callback_provenance(self):
        from services import clustering
        from services.analysis_selection import prepare_selection, finalize_selection, write_json
        with tempfile.TemporaryDirectory() as directory:
            job = Path(directory)
            data = job / 'dataset'
            data.mkdir()
            (job / 'keyword_policy.json').write_bytes(get_policy().source_bytes)
            write_csv(data / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                      [{'번호': 1, '질문': '지하철 악취', '날짜': '2026-11-05', 'logType': 'UNANSWER'}])
            prepare_selection(data, 'IMPROVEMENT')
            write_csv(data / 'preprocessed_full.csv', ['logId', 'normalizedForClustering', 'logType'],
                      [{'logId': 1, 'normalizedForClustering': '지하철 악취', 'logType': 'UNANSWER'}])
            faq = dict(faq_id=42, source_seq_num=6099, question='객실에서 불쾌한 냄새가 날 때', answer='신고 안내')
            decisions = [dict(status='MATCHED', faqId=42, path='SEMANTIC', reason='SAME_APPLICABLE_ANSWER')]
            with patch.multiple(clustering, DATA_DIR=data, INPUT_CSV=data / 'preprocessed_full.csv'), \
                 patch.object(clustering, 'load_model', side_effect=AssertionError('model loaded')), \
                 patch.object(clustering, 'plot_2d', side_effect=lambda *args: args[-1].write_bytes(b'plot')):
                clustering.main(route=lambda texts: (decisions, {42: faq}, None, 'fixture'))
            finalize_selection(data, min_cluster_size=5, min_samples=2, faq_min_size=5)
            expansions = json.loads((data / 'faq_expansions.json').read_text(encoding='utf-8'))
            write_json(data / 'faq_candidates_final.json', {'faqCandidates': expansions})
            result = build_callback(job)
            candidate, = result['faqCandidates']
            self.assertEqual('RESULTS_GENERATED', result['completionReason'])
            self.assertEqual(1, candidate['occurrenceCount'])
            self.assertEqual(42, candidate['matchedFaqs'][0]['matchedFaqId'])
            self.assertEqual([1], candidate['analysisProvenance']['logIds'])
            self.assertEqual([{'text': '지하철 악취', 'type': 'SYNONYM'}], candidate['synonyms'])

    def test_real_preprocessing_and_small_branch_skip_models(self):
        import sys
        from types import SimpleNamespace
        import services
        from services import run_pipeline as pipeline, preprocess, clustering
        retriever = SimpleNamespace(load_kb=lambda: [], FAQ_MATCH_THRESHOLD=.8,
                                    kb_fingerprint=lambda kb: 'fixture',
                                    rank_questions=lambda questions, kb: [[] for _ in questions])
        for text, reason in [('', 'NO_ELIGIBLE_TEXT'), ('환승 문의', 'NO_FAQ_CLUSTERS')]:
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as directory:
                job = Path(directory)
                data = job / 'dataset'
                data.mkdir()
                (job / 'keyword_policy.json').write_bytes(get_policy().source_bytes)
                write_csv(data / 'tota24_unanswered_2026Q1.csv', ['번호', '질문', '날짜', 'logType'],
                          [{'번호': i, '질문': text, '날짜': '2026-11-05', 'logType': 'LOW_QUALITY'} for i in (1, 2)])
                with patch.dict(sys.modules, {'services.retriever': retriever}), \
                     patch.object(services, 'retriever', retriever, create=True), \
                     patch.multiple(pipeline, DATA_DIR=data, OUTPUT_JSON=data / 'faq_candidates_final.json',
                                    LOG_DIR=job / 'logs', RUN_LOG=job / 'logs/run_pipeline.log'), \
                     patch.multiple(preprocess, DATA_DIR=data, OUTPUT_CSV=data / 'preprocessed_full.csv'), \
                     patch.multiple(clustering, DATA_DIR=data, INPUT_CSV=data / 'preprocessed_full.csv'), \
                     patch.object(clustering, 'load_model', side_effect=AssertionError('model loaded')), \
                     patch.object(clustering, 'plot_2d', side_effect=lambda *args: args[-1].write_bytes(b'plot')), \
                     patch.dict(os.environ, {'TTOBAGI_ANALYSIS_MODE': 'IMPROVEMENT',
                                            'TTOBAGI_TRANSLATION_ENABLED': 'false'}):
                    pipeline.run_full_pipeline()
                callback = build_callback(job)
                self.assertEqual(callback['completionReason'], reason)
                self.assertEqual(callback['analysisScope']['targetLogCount'], 2)
                self.assertEqual(callback['systemStatus']['samplingStatus']['lowQuality'], 2)
                self.assertEqual(callback['faqCandidates'], [])

    @unittest.skipUnless(os.getenv('TTOBAGI_TEST_CLUSTERING') == '1', 'Opt-in real UMAP/HDBSCAN boundary test')
    def test_actual_umap_hdbscan_small_boundaries(self):
        import numpy as np
        from services.clustering import reduce_dims, cluster
        rng = np.random.default_rng(42)
        for size in (3, 5, 12, 32):
            with self.subTest(size=size):
                reduced, coordinates = reduce_dims(rng.normal(size=(size, 24)))
                self.assertEqual(coordinates.shape, (size, 2))
                self.assertTrue(np.isfinite(coordinates).all())
                if size >= 5:
                    labels = cluster(reduced)
                    self.assertEqual(len(labels), size)


if __name__ == '__main__':
    unittest.main()
