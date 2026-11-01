import csv
from pathlib import Path
import tempfile
import unittest

from services.convert_chatting_log import convert
from services.log_classification import classify_percent, log_type


class LogClassificationTest(unittest.TestCase):
    def test_boundaries_use_percent_and_do_not_infer_ratios(self):
        for score, expected in [('0', 'UNANSWER'), ('0.75', 'UNANSWER'),
                                ('24.999999', 'UNANSWER'), ('25', 'LOW_QUALITY'),
                                ('25%', 'LOW_QUALITY'), ('74.999999', 'LOW_QUALITY'), ('75', 'CORRECT'),
                                ('75.000001', 'CORRECT'), ('100%', 'CORRECT')]:
            with self.subTest(score=score):
                self.assertEqual(classify_percent(score), expected)

    def test_explicit_labels_take_precedence_and_blank_labels_use_percent(self):
        self.assertEqual(log_type({'logType': 'CORRECT', 'keywordMatchPercent': '0'}), 'CORRECT')
        self.assertEqual(log_type({'logType': '', 'keywordMatchPercent': '75'}), 'CORRECT')
        self.assertEqual(log_type({'keywordMatchPercent': '99.99'}), 'CORRECT')
        self.assertEqual(log_type({'답변확률': '1.0'}), 'UNANSWER')
        for label in (' ', 'OTHER'):
            with self.assertRaises(ValueError):
                log_type({'logType': label, 'keywordMatchPercent': '100'})

    def test_invalid_scores_are_rejected_instead_of_defaulting_to_unanswered(self):
        for value in ('', ' ', None, 'NaN', 'Infinity', '-1', '100.01', '9273', '30%%', '3e1'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                log_type({'keywordMatchPercent': value})
        for value in ('', None, '-1', '10001', '99.99', 'NaN'):
            with self.subTest(raw=value), self.assertRaises(ValueError):
                log_type({'ai_percent': value})

    def test_raw_scores_use_confirmed_scale_and_boundaries(self):
        for score, expected in [('0', 'UNANSWER'), ('2499', 'UNANSWER'),
                                ('2500', 'LOW_QUALITY'), ('7499', 'LOW_QUALITY'),
                                ('7500', 'CORRECT'), ('9999', 'CORRECT'), ('10000', 'CORRECT')]:
            with self.subTest(raw=score):
                self.assertEqual(log_type({'ai_percent': score}), expected)


class ChattingLogConversionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / 'raw.csv'
        self.target = Path(self.temp.name) / 'analysis.csv'
        self.source.write_text(
            'seq_num,kind,msg_m,msg_m_txt,sysdate,ai_percent\n'
            '1,0,원본 질문,재생성 질문,2026-03-01 00:31:52,0\n'
            '2,1,원본 질문,재생성 질문,2026-03-01 00:31:52,2499\n'
            '3,1,질문2,재생성2,2026-03-02 01:00:00,2500\n'
            '4,1,질문3,재생성3,2026-03-02 01:00:00,7500\n'
            '5,1,질문4,재생성4,2026-03-02 01:00:00,9999\n', encoding='cp949')

    def test_confirmed_encoding_selects_responses_preserves_original_question_and_scores(self):
        self.assertEqual(convert(self.source, self.target, encoding='cp949'), 4)
        with self.target.open(encoding='utf-8-sig', newline='') as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual([r['번호'] for r in rows], ['2', '3', '4', '5'])
        self.assertEqual(rows[0]['질문'], '원본 질문')
        self.assertEqual(rows[0]['ai_percent'], '2499')
        self.assertEqual(rows[-1]['ai_percent'], '9999')
        self.assertEqual([log_type(r) for r in rows], ['UNANSWER', 'LOW_QUALITY', 'CORRECT', 'CORRECT'])
        original = self.target.read_bytes()
        with self.assertRaises(FileExistsError):
            convert(self.source, self.target, encoding='cp949')
        self.assertEqual(self.target.read_bytes(), original)

    def test_invalid_raw_score_fails_before_output_is_created(self):
        self.source.write_text(self.source.read_text(encoding='cp949').replace('9999', '10001'), encoding='cp949')
        with self.assertRaises(ValueError):
            convert(self.source, self.target, encoding='cp949')
        self.assertFalse(self.target.exists())

    def test_daily_aggregate_is_rejected(self):
        self.source.write_text('qa_date,answer_cnt,unanswer_cnt\n2026-03-01,10,2\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'daily aggregates'):
            convert(self.source, self.target)
        self.assertFalse(self.target.exists())


if __name__ == '__main__':
    unittest.main()
