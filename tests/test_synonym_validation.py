import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from services.synonym_validation import finalize_candidates, validate_synonyms, verify_audit
from services.synonym_validation import main


class SynonymValidationTest(unittest.TestCase):
    def test_screenshot_regression(self):
        texts = ['무료 와이파이는?', '무료 와이파이 비밀번호는?', '와이파이 비밀번호는?',
                 '와이파이 비번', '와이파이는?']
        accepted, records = validate_synonyms([dict(text=t, type='TYPO') for t in texts])
        self.assertEqual([x['type'] for x in accepted],
                         ['SYNONYM', 'SYNONYM', 'SYNONYM', 'ABBR', 'SYNONYM'])
        self.assertTrue(all(x['status'] == 'RECLASSIFIED' for x in records))

    def test_only_observed_approved_typo_is_confirmed(self):
        item = dict(text='와이파이 비밀번허', type='TYPO')
        self.assertEqual(validate_synonyms([item])[0], [])
        accepted, records = validate_synonyms([item], [dict(text=item['text'], logId='7')])
        self.assertEqual(accepted, [item])
        self.assertEqual(records[0]['sourceLogIds'], ['7'])
        self.assertEqual(records[0]['correctedText'], '와이파이 비밀번호')

    def test_unknown_typo_does_not_become_synonym_or_abbreviation(self):
        accepted, records = validate_synonyms([
            dict(text='새로운표현', type='TYPO'), dict(text='잘못됀 비번', type='TYPO'),
            dict(text='새로운표현', type='WRONG'), dict(text='새로운표현', type='ABBR')])
        self.assertEqual(accepted, [])
        self.assertTrue(all(r['status'] == 'REVIEW_REQUIRED' for r in records))

    def test_invalid_values_duplicates_and_limit(self):
        accepted, _ = validate_synonyms([None, dict(text=1, type='SYNONYM'),
            dict(text=' ', type='SYNONYM'), dict(text='가' * 101, type='SYNONYM'),
            dict(text=' 와이파이 ', type='SYNONYM'), dict(text='와이파이', type='SYNONYM')])
        self.assertEqual(len(accepted), 1)
        self.assertEqual(len(validate_synonyms([
            dict(text=f'정상 표현 {i}', type='SYNONYM') for i in range(12)])[0]), 10)

    def test_abbreviation_and_foreign_alias(self):
        accepted, _ = validate_synonyms([dict(text='WiFi', type='ABBR'),
                                        dict(text='역 와이파이 비번', type='SYNONYM')])
        self.assertEqual([r['type'] for r in accepted], ['SYNONYM', 'ABBR'])

    def test_audit_binds_final_question_and_synonyms(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'clusters.csv').write_text('logId,clusterId,text\n1,3,문의\n', encoding='utf-8')
            candidates = [dict(clusterLabel=3, standardQuestion='질문', synonyms=[
                dict(text='와이파이 비밀번호는?', type='TYPO')])]
            with self.assertRaisesRegex(ValueError, 'missing'):
                verify_audit(candidates, data)
            finalize_candidates(candidates, data)
            verify_audit(candidates, data)
            self.assertEqual(candidates[0]['synonyms'][0]['type'], 'SYNONYM')
            candidates[0]['standardQuestion'] = '변경된 질문'
            with self.assertRaisesRegex(ValueError, 'does not match'):
                verify_audit(candidates, data)
            audit = json.loads((data / 'synonym_validation.json').read_text(encoding='utf-8'))
            self.assertEqual(audit['candidates'][0]['records'][0]['proposedType'], 'TYPO')

    def test_review_command_does_not_overwrite_results(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'clusters.csv').write_text('logId,clusterId,text\n1,3,문의\n', encoding='utf-8')
            final = data / 'faq_candidates_final.json'
            final.write_text(json.dumps({'faqCandidates': [dict(clusterLabel=3, synonyms=[
                dict(text='모르는 오류', type='TYPO')])]}), encoding='utf-8')
            original = final.read_bytes()
            report = data / 'review.json'
            with patch('sys.argv', ['review', '--data', str(data), '--report', str(report)]):
                main()
                with self.assertRaises(SystemExit):
                    main()
            self.assertEqual(original, final.read_bytes())
            self.assertFalse((data / 'synonym_validation.json').exists())
            record = json.loads(report.read_text(encoding='utf-8'))['candidates'][0]['records'][0]
            self.assertEqual(record['status'], 'REVIEW_REQUIRED')


if __name__ == '__main__':
    unittest.main()
