import csv
from pathlib import Path
import tempfile
import unittest

from services.analysis_input import read_analysis_csv
from services.dashboard_metrics import validate_source_rows
from services.log_classification import log_type
from services.run_job import _copy_input


RAW = ('seq_num,kind,msg_m,msg_m_txt,sysdate,ai_percent,logType\n'
       '1,0,원본 질문,재생성 질문,2026-03-01 00:00:00,0,\n'
       '2,1,원본 질문,재생성 질문,2026-03-01 00:00:00,9999,UNANSWER\n'
       '3,1,환승 문의,재생성 질문,2026-03-02 00:00:00,2500,\n'
       '4,1,시간 문의,재생성 질문,2026-03-03 00:00:00,7500,\n')


class AnalysisInputTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name) / 'upload.csv'
        self.target = Path(self.temp.name) / 'worker.csv'

    def test_original_utf8_bom_and_cp949_preserve_labels_and_original_questions(self):
        for encoding in ('utf-8', 'utf-8-sig', 'cp949'):
            with self.subTest(encoding=encoding):
                self.source.write_text(RAW, encoding=encoding)
                fields, rows = read_analysis_csv(self.source)
                validate_source_rows(rows)
                self.assertIn('번호', fields)
                self.assertEqual([r['번호'] for r in rows], ['2', '3', '4'])
                self.assertEqual(rows[0]['질문'], '원본 질문')
                self.assertEqual([log_type(r) for r in rows], ['UNANSWER', 'LOW_QUALITY', 'CORRECT'])
                self.assertEqual(self.source.read_text(encoding=encoding), RAW)

    def test_worker_normalizes_before_period_filter_and_with_no_period(self):
        self.source.write_text(RAW, encoding='cp949')
        _copy_input(self.source, self.target, '2026-03-02', '2026-03-03')
        fields, rows = read_analysis_csv(self.target)
        self.assertEqual([r['번호'] for r in rows], ['3', '4'])
        _copy_input(self.source, self.target)
        self.assertEqual(len(read_analysis_csv(self.target)[1]), 3)

    def test_canonical_input_does_not_filter_kind_or_discard_training_labels(self):
        self.source.write_text('번호,질문,날짜,kind,logType,답변확률\n1,질문,2026-03-01,0,CORRECT,0.1\n', encoding='cp949')
        fields, rows = read_analysis_csv(self.source)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['답변확률'], '0.1')
        self.assertEqual(log_type(rows[0]), 'CORRECT')

    def test_daily_aggregates_invalid_kind_and_mixed_headers_are_rejected(self):
        for text in ('qa_date,answer_cnt,unanswer_cnt\n2026-03-01,10,2\n',
                     RAW.replace('2,1,', '2,99,'),
                     RAW.replace('seq_num,', '번호,seq_num,')):
            self.source.write_text(text, encoding='utf-8')
            with self.subTest(text=text[:40]), self.assertRaises(ValueError):
                read_analysis_csv(self.source)


if __name__ == '__main__':
    unittest.main()
