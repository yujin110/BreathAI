import csv
import json
from pathlib import Path
import tempfile
import unittest

from evaluation.clustering_quality import summarize


class ClusteringQualityTest(unittest.TestCase):
    def test_measured_pairs_noise_log_coverage_and_missing_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            for name, fields, rows in [
                ('preprocessed_full.csv', ['logId', 'normalizedForClustering'],
                 [[1, '환기'], [2, '환기'], [3, '냉방'], [4, '']]),
                ('clusters.csv', ['logId', 'clusterId', 'text'],
                 [[1, 5, '환기'], [2, 5, '환기'], [3, -1, '냉방']]),
            ]:
                with (data / name).open('w', encoding='utf-8-sig', newline='') as stream:
                    writer = csv.writer(stream)
                    writer.writerow(fields)
                    writer.writerows(rows)
            (data / 'unclustered.json').write_text(json.dumps([dict(sourceLogSeqNum=4)]))
            result, inputs = summarize(data, {'pairs': [
                dict(leftLogId=1, rightLogId=2, sameIntent=True),
                dict(leftLogId=1, rightLogId=3, sameIntent=False)]})
            self.assertEqual(len(inputs), 4)
            self.assertEqual(result['logPreservationRate'], 1)
            self.assertEqual(result['identicalQuestionConsistency'], 1)
            self.assertEqual(result['falseMergeRate'], 0)
            self.assertEqual(result['sameIntentMergeRecall'], 1)
            self.assertAlmostEqual(result['originalNoiseRate'], 1/3)
            self.assertEqual(result['uniqueNoiseRate'], .5)
            self.assertIsNone(result['expansionAccuracy'])
            missing, _ = summarize(data, {})
            self.assertIsNone(missing['falseMergeRate'])
            self.assertIsNone(missing['sameIntentMergeRecall'])
            (data / 'faq_candidates.json').write_text(json.dumps({'faqCandidates': [dict(
                candidateId='candidate:99', clusterLabel=5, candidateType='EXPAND',
                standardQuestion='환기', tag='FAQ')]}), encoding='utf-8')
            measured, _ = summarize(data, {'matches': [dict(logId=1, candidateType='EXPAND')]})
            self.assertEqual(measured['expansionAccuracy'], 1)
            self.assertEqual(measured['evaluatedMatchCount'], 1)
