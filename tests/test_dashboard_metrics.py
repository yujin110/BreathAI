import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from services.callback_payload import build_callback
from services.dashboard_metrics import build_dashboard, _burst_keywords
from services.keyword_policy import KeywordPolicy, DEFAULT_POLICY_PATH


def write_csv(path, names, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.DictWriter(output, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)


class DashboardMetricsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.job = Path(self.temp.name)
        self.data = self.job / 'dataset'
        self.data.mkdir()
        self.rows = [
            {'번호': 10, '질문': 'raw email@example.com', '날짜': '2026-10-01', 'logType': 'CORRECT'},
            {'번호': 20, '질문': '저품질', '날짜': '2026-10-02', 'logType': 'LOW_QUALITY'},
            {'번호': 30, '질문': '노이즈', '날짜': '2026-10-02', 'logType': 'UNANSWER'},
            {'번호': 40, '질문': 'ㅋ', '날짜': '2026-10-02', 'logType': 'UNANSWER'},
        ]
        self.prepared = [{'logId': r['번호'], 'normalizedForClustering': '[EMAIL]' if i == 0 else ('문의' if i < 3 else '')}
                         for i, r in enumerate(self.rows)]
        self.clustered = [{'logId': r['번호'], 'clusterId': 0 if i < 2 else -1,
                           'umap_x': i + .25, 'umap_y': -i, 'text': 'unused raw text'}
                          for i, r in enumerate(self.rows[:3])]
        self.summaries = {0: {'size': '2'}, -1: {'size': '1'}}
        self.save()

    def save(self):
        write_csv(self.data / 'tota24_unanswered_2026Q1.csv', self.rows[0].keys(), self.rows)
        write_csv(self.data / 'preprocessed_full.csv', self.prepared[0].keys(), self.prepared)
        write_csv(self.data / 'clusters.csv', self.clustered[0].keys(), self.clustered)

    def test_all_coordinates_noise_masking_and_full_input_totals(self):
        result = build_dashboard(self.data, {'0': ['문의']}, self.summaries)
        self.assertEqual([p['sourceLogSeqNum'] for p in result['points']], [10, 20, 30])
        self.assertEqual(result['points'][2]['clusterLabel'], -1)
        self.assertEqual(result['points'][0]['text'], '[EMAIL]')
        self.assertEqual(result['systemStatus'], {'totalLogCount': 4, 'samplingStatus':
            {'correct': 1, 'lowQuality': 1, 'unanswer': 2}, 'visualizedLogCount': 3,
            'excludedLogCount': 1, 'isSampled': False})
        self.assertEqual(result['performanceMetrics']['currentUnanswerRate'], 50)
        self.assertIsNone(result['performanceMetrics']['predictedAccuracyGain'])
        self.assertEqual(sum(r['totalCnt'] for r in result['usageStats']), 4)
        self.assertEqual(result['usageStats'][1]['unanswerCnt'], 2)
        self.assertEqual([row['statDate'] for row in result['usageStats']], ['2026-10-01', '2026-10-02'])
        for row in result['usageStats']:
            self.assertEqual(row['totalCnt'], row['answerCnt'] + row['lowQualityCount'] + row['unanswerCnt'])
        self.assertEqual(result['metricsScope'], 'ANALYSIS_INPUT')

    def test_unanswered_contract_does_not_reinterpret_training_labels(self):
        for row in self.rows:
            row.pop('logType')
            row['답변확률'] = '1.0'
            row['질의 유효/무효(유효:1, 무효:0)'] = '1'
        self.save()
        result = build_dashboard(self.data, {}, self.summaries)
        self.assertEqual(result['systemStatus']['samplingStatus']['unanswer'], 4)
        self.assertEqual(result['performanceMetrics']['currentUnanswerRate'], 100)

    def test_missing_baseline_does_not_fabricate_bursts(self):
        result = build_dashboard(self.data, {'0': ['문의']}, self.summaries)
        self.assertEqual(result['burstKeywords'], [])
        self.assertEqual(result['surgeDetection'], [])

    def test_burst_counts_and_zero_baseline_without_infinite_rate(self):
        from datetime import date
        dates = {1: date(2026, 10, 1), **{i: date(2026, 10, 8) for i in range(2, 6)}}
        points = [{'sourceLogSeqNum': i, 'clusterLabel': 0, 'text': '환불 환불' + (' 신규' if i > 1 else '')}
                  for i in range(1, 6)]
        bursts, surges = _burst_keywords(points, dates, {'0': ['환불', '신규']}, dates.values())
        values = {r['keyword']: r for r in bursts}
        self.assertEqual(values['환불']['count'], 4)  # once per log, not twice per phrase
        self.assertEqual(values['환불']['increasedRate'], 300)
        self.assertIsNone(values['신규']['increasedRate'])
        self.assertEqual([r['keyword'] for r in bursts], [r['keyword'] for r in surges])
        for summary, detail in zip(bursts, surges):
            self.assertEqual(summary['count'], detail['keywordCount'])
            self.assertEqual(summary['increasedRate'], detail['increasedRate'])
        self.assertEqual(len(surges[0]['periodStats']), 14)
        self.assertEqual({r['periodType'] for r in surges[0]['periodStats']}, {'BASE', 'PREV'})

    def test_invalid_ids_labels_and_coordinates_fail_instead_of_dropping_rows(self):
        for field, value in [('logId', '999'), ('clusterId', '99'), ('umap_x', 'nan')]:
            with self.subTest(field=field):
                previous = self.clustered[0][field]
                self.clustered[0][field] = value
                self.save()
                with self.assertRaises(ValueError):
                    build_dashboard(self.data, {}, self.summaries)
                self.clustered[0][field] = previous
        self.rows[1]['번호'] = 10
        self.save()
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            build_dashboard(self.data, {}, self.summaries)

    def write_callback_artifacts(self, keywords=None):
        from services.synonym_validation import finalize_candidates
        finalize_candidates([], self.data)
        (self.data / 'faq_candidates_final.json').write_text('{"faqCandidates": []}')
        (self.data / 'cluster_keywords.json').write_text(json.dumps(keywords or {'0': ['문의']}), encoding='utf-8')
        write_csv(self.data / 'cluster_summary.csv', ['clusterId', 'size'],
                  [{'clusterId': key, **value} for key, value in self.summaries.items()])

    def test_completed_payload_contains_dashboard_and_noise_cluster(self):
        self.write_callback_artifacts()
        payload = build_callback(self.job)
        self.assertEqual(payload['status'], 'COMPLETED')
        self.assertEqual(len(payload['clusteringView']['points']), 3)
        self.assertIn(-1, [r['clusterLabel'] for r in payload['clusteringView']['clusterNames']])
        json.dumps(payload, allow_nan=False)

    def test_snapshot_policy_used_by_real_callback_and_audit_not_recounted(self):
        import numpy as np
        from services.clustering import extract_keywords
        definition = json.loads(DEFAULT_POLICY_PATH.read_text(encoding='utf-8'))
        definition['version'] = 'test-stop-there'
        definition['stopwords'].append('there')
        snapshot = self.job / 'keyword_policy.json'
        snapshot.write_text(json.dumps(definition), encoding='utf-8')
        policy = KeywordPolicy.load(snapshot)
        for index, row in enumerate(self.rows):
            row['날짜'] = '2026-10-01' if index == 0 else '2026-10-08'
            self.prepared[index]['normalizedForClustering'] = 'theater' if index == 0 else 'there Wi-Fi WiFi'
        self.clustered.append({'logId': 40, 'clusterId': -1, 'umap_x': 1, 'umap_y': 1, 'text': 'unused'})
        self.summaries[-1]['size'] = '2'
        self.save()
        audit_path = self.data / 'keyword_policy_audit.json'
        keywords, _ = extract_keywords([r['normalizedForClustering'] for r in self.prepared],
                                      np.array([0, 0, -1, -1]), policy=policy, audit_path=audit_path)
        self.write_callback_artifacts({**{str(k): v for k, v in keywords.items()}, 'injected': ['there', 'wi fi']})
        audit_before = audit_path.read_bytes()
        with patch('services.callback_payload.get_policy', side_effect=AssertionError('Must use snapshot')):
            payload = build_callback(self.job)
        self.assertEqual(payload['burstKeywords'], [{'keyword': 'wifi', 'increasedRate': None, 'count': 3}])
        self.assertEqual(audit_path.read_bytes(), audit_before)
        self.assertEqual(json.loads(audit_before)['excludedEnglishTokenOccurrences'], 3)
        self.assertEqual(payload['clusteringView']['points'][1]['text'], 'there Wi-Fi WiFi')
        self.assertNotIn('policyVersion', payload)

    def test_mismatched_or_missing_snapshot_audit_never_publishes_success(self):
        self.write_callback_artifacts()
        snapshot = self.job / 'keyword_policy.json'
        snapshot.write_bytes(DEFAULT_POLICY_PATH.read_bytes())
        audit_path = self.data / 'keyword_policy_audit.json'
        with self.assertRaises(FileNotFoundError):
            build_callback(self.job)
        audit_path.write_text(json.dumps({'policyVersion': 'en-v1', 'policySha256': 'wrong'}))
        with self.assertRaisesRegex(ValueError, 'does not match'):
            build_callback(self.job)
        snapshot.unlink()
        with self.assertRaisesRegex(ValueError, 'snapshot is missing'):
            build_callback(self.job)


if __name__ == '__main__':
    unittest.main()
