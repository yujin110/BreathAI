"""Endpoint/queue/worker/callback tests; optional HTTP transport smoke test."""
import csv
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

import httpx
from fastapi import HTTPException, UploadFile
from fastapi.testclient import TestClient

from services.pipeline_api import create_app
from services.pipeline_worker import deliver_one, process_one, worker_lock
from services.run_job import _copy_input
from services.dashboard_metrics import build_dashboard
from tests.test_analysis_input import RAW

CSV = '번호,질문,날짜\n1,문의,2026-10-17\n'.encode('utf-8')


class EndpointClient:
    """Call route functions and auth directly without a socket-based event loop.

    This does not test FastAPI's multipart parsing; the opt-in smoke test does.
    """
    def __init__(self, app):
        self.app = app

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def close(self):
        pass

    def request(self, method, path, headers=None, data=None, files=None):
        route_path = '/jobs/{analysis_id}' if path.startswith('/jobs/') else path
        route = next(route for route in self.app.routes if route.path == route_path)
        try:
            for dependency in route.dependencies:
                dependency.dependency((headers or {}).get('Authorization'))
            if method == 'GET':
                result = route.endpoint(int(path.rsplit('/', 1)[1]))
            else:
                values = dict(data or {})
                values['analysisId'] = int(values['analysisId'])
                values['file'] = UploadFile(file=io.BytesIO(files['file'][1]), filename=files['file'][0])
                for field, default in [('isMaskingEnabled', True), ('isTranslationEnabled', False)]:
                    values[field] = str(values.get(field, str(default))).lower() == 'true'
                values['minClusterSize'] = int(values.get('minClusterSize', 5))
                values['minSamples'] = int(values.get('minSamples', 2))
                values.setdefault('periodStartDate', '')
                values.setdefault('periodEndDate', '')
                result = route.endpoint(**values)
            return httpx.Response(route.status_code or 200, json=result)
        except HTTPException as exc:
            return httpx.Response(exc.status_code, json={'detail': exc.detail})

    def get(self, path, **kwargs):
        return self.request('GET', path, **kwargs)

    def post(self, path, **kwargs):
        return self.request('POST', path, **kwargs)


class PipelineApiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.app = create_app(root=self.root, token="test-secret", max_upload_bytes=1024)
        self.client = EndpointClient(self.app)
        self.auth = {"Authorization": "Bearer test-secret"}

    def tearDown(self):
        self.client.close()
        self.temp.cleanup()

    def submit(self, **overrides):
        data = {"analysisId": "1", "uploadId": "upload-1", "isMaskingEnabled": "true",
                "isTranslationEnabled": "false", "periodStartDate": "2026-10-01", "periodEndDate": "2026-10-31"}
        data.update(overrides)
        return self.client.post("/pipeline", headers=self.auth, data=data, files={"file": ("../input.csv", CSV)})

    def test_clustering_parameters_reach_worker_and_change_job_identity(self):
        self.assertEqual(self.submit(minClusterSize="12", minSamples="4").status_code, 202)
        seen = {}
        def runner(*args, **options):
            seen.update(options)
            return 1
        process_one(self.app.state.store, runner=runner)
        self.assertEqual(seen["min_cluster_size"], 12)
        self.assertEqual(seen["min_samples"], 4)
        self.assertEqual(self.submit(minClusterSize="13", minSamples="4").status_code, 409)

    def test_invalid_clustering_parameters_are_rejected(self):
        self.assertEqual(self.submit(minClusterSize="1").status_code, 422)
        self.assertEqual(self.submit(minSamples="0").status_code, 422)
        self.assertIsNone(self.app.state.store.get(1))

    def test_auth_upload_validation_and_private_status(self):
        self.assertEqual(self.client.post("/pipeline", data={"uploadId": "x", "analysisId": "1"},
                                         files={"file": ("x.csv", CSV)}).status_code, 401)
        self.assertEqual(self.client.get("/jobs/1").status_code, 401)
        self.assertEqual(self.submit(periodEndDate="2026-01-01").status_code, 422)
        self.assertEqual(self.submit(analysisId="-1").status_code, 422)
        for content in (b"", b"wrong,header", b"x" * 1025):
            response = self.client.post("/pipeline", headers=self.auth, data={"uploadId": "x", "analysisId": "1"},
                                        files={"file": ("x.csv", content)})
            self.assertIn(response.status_code, (413, 422))
        self.assertEqual(list((self.root / "uploads").iterdir()), [])

    def test_duplicate_persists_across_app_restart_and_conflicting_options_fail(self):
        self.assertEqual(self.submit().status_code, 202)
        self.assertTrue(self.submit().json()["duplicate"])
        self.assertEqual(self.submit(isTranslationEnabled="true").status_code, 409)
        with EndpointClient(create_app(root=self.root, token="test-secret")) as restarted:
            self.assertEqual(restarted.get("/jobs/1", headers=self.auth).json()["status"], "QUEUED")
        self.assertEqual(len(list((self.root / "uploads").iterdir())), 1)

    def test_invalid_source_ids_dates_and_log_types_rejected_before_queueing(self):
        for rows in ('1,문의,2026-10-17\n1,다른 문의,2026-10-17\n',
                     '2147483648,문의,2026-10-17\n', '1,문의,bad-date\n'):
            response = self.client.post('/pipeline', headers=self.auth,
                data={'analysisId': '2', 'uploadId': 'invalid'},
                files={'file': ('input.csv', ('번호,질문,날짜\n' + rows).encode('utf-8'))})
            self.assertEqual(response.status_code, 422)
        self.assertIsNone(self.app.state.store.get(2))

    def test_percent_upload_validation_accepts_confirmed_raw_scores(self):
        for analysis_id, score in enumerate(('0', '24.99', '25', '74.99', '75%', '100'), start=10):
            response = self.client.post('/pipeline', headers=self.auth,
                data={'analysisId': str(analysis_id), 'uploadId': 'percent'},
                files={'file': ('input.csv', f'번호,질문,날짜,keywordMatchPercent\n1,문의,2026-10-17,{score}\n'.encode('utf-8'))})
            self.assertEqual(response.status_code, 202)
        for column, score in [('keywordMatchPercent', ''), ('keywordMatchPercent', '9999'),
                              ('keywordMatchPercent', 'NaN'), ('ai_percent', '10001')]:
            response = self.client.post('/pipeline', headers=self.auth,
                data={'analysisId': '3', 'uploadId': 'invalid-percent'},
                files={'file': ('input.csv', f'번호,질문,날짜,{column}\n1,문의,2026-10-17,{score}\n'.encode('utf-8'))})
            self.assertEqual(response.status_code, 422)
        self.assertIsNone(self.app.state.store.get(3))
        response = self.client.post('/pipeline', headers=self.auth,
            data={'analysisId': '4', 'uploadId': 'raw-score'},
            files={'file': ('input.csv', '번호,질문,날짜,ai_percent\n1,문의,2026-10-17,9999\n'.encode('utf-8'))})
        self.assertEqual(response.status_code, 202)

    def test_original_csv_upload_automatically_reaches_dashboard_with_label_precedence(self):
        for analysis_id, encoding in enumerate(('utf-8-sig', 'cp949'), start=20):
            response = self.client.post('/pipeline', headers=self.auth,
                data={'analysisId': str(analysis_id), 'uploadId': 'original'},
                files={'file': ('chatting_log.csv', RAW.encode(encoding))})
            self.assertEqual(response.status_code, 202)
            queued = Path(self.app.state.store.get(analysis_id)['input_path'])
            data = self.root / f'dashboard-{analysis_id}'
            data.mkdir()
            _copy_input(queued, data / 'tota24_unanswered_2026Q1.csv')
            with (data / 'tota24_unanswered_2026Q1.csv').open(encoding='utf-8-sig', newline='') as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual([r['번호'] for r in rows], ['2', '3', '4'])
            (data / 'preprocessed_full.csv').write_text(
                'logId,normalizedForClustering\n2,문의\n3,환승 문의\n4,시간 문의\n', encoding='utf-8')
            (data / 'clusters.csv').write_text(
                'logId,clusterId,umap_x,umap_y\n2,0,0,0\n3,0,1,1\n4,0,2,2\n', encoding='utf-8')
            dashboard = build_dashboard(data, {}, {0: {'size': '3'}})
            self.assertEqual(dashboard['systemStatus']['samplingStatus'],
                             {'correct': 1, 'lowQuality': 1, 'unanswer': 1})
            self.assertEqual([p['logType'] for p in dashboard['points']], ['UNANSWER', 'LOW_QUALITY', 'CORRECT'])
            self.assertEqual(dashboard['usageStats'][0]['unanswerCnt'], 1)
            # The first response has a high score but its explicit UNANSWER wins.
            self.assertEqual(rows[0]['ai_percent'], '9999')
            self.assertEqual(rows[0]['logType'], 'UNANSWER')

    def test_daily_aggregate_upload_is_rejected_before_queueing(self):
        response = self.client.post('/pipeline', headers=self.auth,
            data={'analysisId': '30', 'uploadId': 'daily'},
            files={'file': ('chatting_log_day.csv', b'qa_date,answer_cnt,unanswer_cnt\n2026-03-01,10,2\n')})
        self.assertEqual(response.status_code, 422)
        self.assertIsNone(self.app.state.store.get(30))

    def fake_inference(self, source, job, **options):
        self.assertTrue(options["masking"])
        self.assertFalse(options["translation"])
        self.assertEqual(options["period_start"], "2026-10-01")
        data = job / "dataset"
        data.mkdir(parents=True)
        (data / "faq_candidates_final.json").write_text(json.dumps({"faqCandidates": [{
            "clusterLabel": 3, "candidateType": "NEW", "standardQuestion": "질문", "answerDraft": "답변",
            "topKeywords": ["키워드"], "synonyms": [{"text": "동의어", "type": "SYNONYM"}]}]}), encoding="utf-8")
        (data / "cluster_keywords.json").write_text('{"3": ["키워드"]}', encoding="utf-8")
        (data / "cluster_summary.csv").write_text("clusterId,size,isNoise\n3,5,False\n", encoding="utf-8")
        (data / 'tota24_unanswered_2026Q1.csv').write_text(
            '번호,질문,날짜\n' + ''.join(f'{i},문의,2026-10-17\n' for i in range(1, 6)), encoding='utf-8')
        (data / 'preprocessed_full.csv').write_text(
            'logId,normalizedForClustering\n' + ''.join(f'{i},문의\n' for i in range(1, 6)), encoding='utf-8')
        (data / 'clusters.csv').write_text(
            'logId,clusterId,umap_x,umap_y,text\n' + ''.join(f'{i},3,{i},0,문의\n' for i in range(1, 6)), encoding='utf-8')
        return 0

    def test_full_request_to_callback_contract_without_gpu(self):
        self.submit()
        store = self.app.state.store
        self.assertTrue(process_one(store, self.fake_inference))
        self.assertFalse(process_one(store, self.fake_inference))
        received = []

        def backend(request):
            self.assertEqual(request.url.path, "/api/v1/dashboard/analyze/callback/1")
            self.assertEqual(request.headers["authorization"], "Bearer callback-secret")
            received.append(json.loads(request.content))
            return httpx.Response(200, json={"success": True})

        with httpx.Client(transport=httpx.MockTransport(backend)) as client:
            self.assertTrue(deliver_one(store, "https://be.example", "callback-secret", client))
            self.assertFalse(deliver_one(store, "https://be.example", "callback-secret", client))
        self.assertEqual(received[0]["status"], "COMPLETED")
        self.assertEqual(received[0]["faqCandidates"][0]["occurrenceCount"], 5)
        self.assertEqual(received[0]["faqCandidates"][0]["representativeKeywords"], ["키워드"])
        self.assertEqual(len(received[0]["clusteringView"]["points"]), 5)
        self.assertEqual(received[0]['systemStatus']['totalLogCount'], 5)
        self.assertEqual(received[0]['performanceMetrics']['currentUnanswerRate'], 100)
        self.assertEqual(store.get(1)["delivery"], "DELIVERED")

    def test_failed_inference_and_ambiguous_callback_are_not_retried(self):
        self.submit()
        store = self.app.state.store
        process_one(store, lambda *a, **k: 7)
        self.assertEqual(store.get(1)["state"], "FAILED")
        calls = []

        def timeout(request):
            calls.append(request)
            raise httpx.ReadTimeout("BE may have committed")

        with httpx.Client(transport=httpx.MockTransport(timeout)) as client:
            deliver_one(store, "https://be.example", "token", client)
            self.assertFalse(deliver_one(store, "https://be.example", "token", client))
        self.assertEqual(len(calls), 1)
        self.assertEqual(store.get(1)["delivery"], "UNKNOWN")

    def test_worker_lock_and_unclean_job_block_second_worker(self):
        with worker_lock(self.root):
            with self.assertRaises(OSError):
                with worker_lock(self.root):
                    self.fail("Second worker acquired lock")
        self.submit()
        store = self.app.state.store
        self.assertIsNotNone(store.claim())
        with self.assertRaises(RuntimeError):
            store.claim()
        store.set_delivery(1, "SENDING")
        store.recover_callbacks()
        self.assertEqual(store.get(1)["delivery"], "UNKNOWN")

    def test_period_filters_inclusively_and_empty_range_fails(self):
        source, target = self.root / "input.csv", self.root / "output.csv"
        source.write_text("번호,질문,날짜\n1,A,2026-10-01\n2,B,2026-10-17\n3,C,2026-11-01\n", encoding="utf-8")
        _copy_input(source, target, "2026-10-01", "2026-10-17")
        with target.open(encoding="utf-8-sig", newline="") as file:
            self.assertEqual([row["번호"] for row in csv.DictReader(file)], ["1", "2"])
        with self.assertRaises(ValueError):
            _copy_input(source, target, "2027-01-01", "2027-02-01")

    @unittest.skipUnless(os.getenv('TTOBAGI_TEST_HTTP') == '1', 'Requires a functioning local async/socket runtime')
    def test_real_multipart_http_transport(self):
        with TestClient(self.app) as client:
            response = client.post('/pipeline', headers=self.auth,
                                   data={'uploadId': 'http-upload', 'analysisId': '99'},
                                   files={'file': ('input.csv', CSV)})
            self.assertEqual(response.status_code, 202)
            self.assertEqual(client.get('/jobs/99', headers=self.auth).json()['status'], 'QUEUED')


if __name__ == "__main__":
    unittest.main()
