import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from services.faq_kb import load_approved_faqs, load_base_kb, merge_kb, load_current_kb
from services.faq_kb import _read_general
from services.faq_kb import _default_connection_factory


class FakeCursor:
    def __init__(self, rows):
        self.rows = rows
        self.sql = None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def execute(self, sql):
        self.sql = sql

    def fetchall(self):
        return self.rows


class FakeConnection:
    def __init__(self, rows):
        self.fake_cursor = FakeCursor(rows)
        self.closed = False

    def cursor(self):
        return self.fake_cursor

    def close(self):
        self.closed = True


class FaqKbTest(unittest.TestCase):
    def test_db_copies_are_available_when_source_files_are_absent(self):
        captured = FakeConnection([])
        load_approved_faqs(lambda: captured, include_source_copies=True)
        with sqlite3.connect(':memory:') as db:
            db.executescript('''
                CREATE TABLE gold_faq (faq_id,source_seq_num,q_type,category,question,answer,keywords,synonyms,is_active);
                INSERT INTO gold_faq VALUES (1,100,1,NULL,'seeded','answer','[]','[]',1),
                                           (2,101,1,NULL,'inactive','answer','[]','[]',0);
            ''')
            self.assertEqual([1], [r[0] for r in db.execute(captured.fake_cursor.sql)])

    def test_operational_ids_remain_searchable_with_handcrafted_records(self):
        with patch('services.faq_kb.load_base_kb', return_value=[]), \
             patch('services.faq_kb.load_handcrafted_faqs') as sources, \
             patch('services.faq_kb.load_approved_faqs', return_value=[]) as approved:
            sources.return_value = ([], {})
            load_current_kb(Path('missing'))
            approved.assert_called_with(None, include_source_copies=True)
            sources.return_value = ([{'question': 'original', 'text': 'answer'}], {})
            load_current_kb(Path('missing'))
            approved.assert_called_with(None, include_source_copies=True)

    def test_explicit_env_file_uses_shared_db_settings(self):
        with patch.dict("os.environ", {"TTOBAGI_DB_ENV_FILE": "ttobagi-server/.env"}), \
                patch("services.bronze_faq_ingest.connect_db") as connect:
            self.assertIs(connect.return_value, _default_connection_factory())
            connect.assert_called_once_with("ttobagi-server/.env")

    def test_loads_approved_faq_as_search_document(self):
        conn = FakeConnection([
            {
                "faq_id": 7,
                "question": "분실물은 어디서 찾나요?",
                "answer": "유실물센터에서 확인해 주세요.",
                "keywords": json.dumps(["분실물", "유실물"], ensure_ascii=False),
                "synonyms": '["열차에 두고 내렸어요"]',
            }
        ])

        records = load_approved_faqs(lambda: conn)

        self.assertEqual(1, len(records))
        self.assertEqual("gold_faq:7", records[0]["chunk_id"])
        self.assertIn("분실물, 유실물", records[0]["text"])
        self.assertEqual(["열차에 두고 내렸어요"], records[0]["synonyms"])
        self.assertTrue(conn.closed)
        self.assertIn("WHERE f.is_active = 1", conn.fake_cursor.sql)

    def test_query_excludes_untouched_copies_without_duplicating_history(self):
        captured = FakeConnection([])
        load_approved_faqs(lambda: captured)
        with sqlite3.connect(":memory:") as conn:
            conn.executescript("""
                CREATE TABLE gold_faq (
                    faq_id INTEGER, source_seq_num INTEGER, q_type INTEGER,
                    category TEXT, question TEXT, answer TEXT, keywords TEXT, synonyms TEXT,
                    is_active INTEGER
                );
                CREATE TABLE gold_faq_edit_history (faq_id INTEGER);
                INSERT INTO gold_faq VALUES
                    (1, NULL, 1, 'category', 'new', 'answer', '[]', '[]', 1),
                    (2, 100, 1, 'category', 'copied', 'answer', '[]', '[]', 1),
                    (3, 101, 1, 'category', 'edited', 'answer', '[]', '[]', 1),
                    (4, NULL, 1, 'category', 'inactive new', 'answer', '[]', '[]', 0),
                    (5, 102, 1, 'category', 'inactive edit', 'answer', '[]', '[]', 0);
                INSERT INTO gold_faq_edit_history VALUES (3), (3), (5);
            """)
            rows = conn.execute(captured.fake_cursor.sql).fetchall()
        self.assertEqual([1, 3], [row[0] for row in rows])

    def test_approved_faq_replaces_same_question_from_base(self):
        base = [{"question": "같은 질문", "text": "오래된 답변"}]
        approved = [{"question": "같은  질문", "text": "승인된 답변"}]

        merged = merge_kb(base, approved)

        self.assertEqual(approved, merged)

    def test_missing_base_file_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            records = load_base_kb(Path(directory) / "missing.jsonl")
        self.assertEqual([], records)

    def test_edited_question_replaces_original_by_source_id(self):
        original = {"source_seq_num": 10, "question": "old question"}
        unrelated = {"source_seq_num": 11, "question": "other question"}
        edited = {"source_seq_num": 10, "question": "changed question"}
        self.assertEqual([unrelated, edited], merge_kb([original, unrelated], [edited]))

    def test_general_similar_questions_are_searchable_but_not_keywords(self):
        import pandas as pd
        values = [1, "admin", "category", "title", "question", "answer",
                  "similar question", "similar question"] + [None] * 8
        with patch("pandas.read_excel", return_value=pd.DataFrame([values])):
            rows, _ = _read_general(Path("general.xlsx"), [])
        self.assertEqual([], rows[0]["keywords"])
        self.assertEqual(["similar question"], rows[0]["similar_questions"])
        self.assertIn("유사질문: similar question", rows[0]["text"])


if __name__ == "__main__":
    unittest.main()


class KbHtmlMemoryTests(unittest.TestCase):
    def test_all_sources_remove_base64_and_html_without_mutation(self):
        source = {"text": '<p>안내</p><img src="data:image/png;base64,' + 'A' * 10000 + '"><script>hidden()</script><p>끝</p>', "question": "&lt;b&gt;질문&lt;/b&gt;"}
        from services.faq_kb import sanitize_kb
        cleaned = sanitize_kb([source])
        self.assertEqual(cleaned[0]["text"], "안내 끝")
        self.assertEqual(cleaned[0]["question"], "질문")
        self.assertIn("base64", source["text"])

    def test_approved_faq_is_cleaned(self):
        from services.faq_kb import _faq_to_kb_record
        record = _faq_to_kb_record({"faq_id": 1, "question": "<b>질문</b>", "answer": '<img src="data:image/png;base64,AAAA"><p>답변</p>'})
        self.assertEqual(record["answer"], "답변")
        self.assertNotIn("<", record["text"])
