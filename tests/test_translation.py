import io
import json
import os
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

from services.translation import TranslationError, translate_with_azure


class AzureTranslationTest(unittest.TestCase):
    @patch.dict(os.environ, {'TTOBAGI_AZURE_TRANSLATOR_KEY': 'test-key'})
    @patch('services.translation.urlopen')
    def test_bike_boarding_is_disambiguated_without_rewriting_riding_questions(self, request):
        cases = [
            ('Can I take a bicycle on the subway?', 'Can I bring a bicycle aboard the subway?'),
            ('Can I ride a bicycle in the subway?', 'Can I ride a bicycle in the subway?'),
        ]
        for question, expected in cases:
            with self.subTest(question=question):
                request.return_value = io.BytesIO(b'[{"translations":[{"text":"test response"}]}]')
                translate_with_azure(question)
                self.assertEqual(json.loads(request.call_args.args[0].data), [{'Text': expected}])

    @patch.dict(os.environ, {'TTOBAGI_AZURE_TRANSLATOR_KEY': 'test-key'})
    @patch('services.translation.time.sleep')
    @patch('services.translation.urlopen')
    def test_retries_throttle_and_returns_korean_translation(self, request, sleep):
        request.side_effect = [
            HTTPError('', 429, 'Too Many Requests', {'Retry-After': '3'}, None),
            io.BytesIO(json.dumps([{'translations': [{'text': '역이 어디에 있나요?'}]}]).encode()),
        ]
        self.assertEqual(translate_with_azure('駅はどこですか？'), '역이 어디에 있나요?')
        sleep.assert_called_once_with(3)
        sent = request.call_args.args[0]
        self.assertEqual(json.loads(sent.data), [{'Text': '駅はどこですか？'}])

    @patch.dict(os.environ, {'TTOBAGI_AZURE_TRANSLATOR_KEY': 'test-key'})
    @patch('services.translation.urlopen')
    def test_bad_credentials_fail_without_retry_or_exposing_request_details(self, request):
        request.side_effect = HTTPError('', 401, 'request details', {}, None)
        with self.assertRaisesRegex(TranslationError, '^Azure Translator returned HTTP 401$'):
            translate_with_azure('Where is the station?')
        request.assert_called_once()


@unittest.skipUnless(os.environ.get('TTOBAGI_TEST_AZURE_LIVE') == '1', 'Azure live test is opt-in')
class AzureLiveTranslationTest(unittest.TestCase):
    def test_foreign_questions_reach_preprocessed_output_in_korean(self):
        from services import preprocess

        # Each group lists acceptable translations of a fact that must survive.
        samples = [
            ('en', 'Where is exit 3 at Seoul Station?', (('서울역', '서울 역'), ('3',), ('출구',))),
            ('en', 'Can I take a bicycle on the subway?',
             (('자전거',), ('지하철',), ('반입', '가져', '가지고', '들고', '싣', '휴대'))),
            ('ja', '弘大入口駅で2号線から空港鉄道に乗り換えられますか？',
             (('홍대입구', '홍대 입구'), ('2',), ('공항철도', '공항 철도'), ('환승', '갈아'))),
            ('ja', '地下鉄に傘を忘れました。どこに連絡すればいいですか？',
             (('우산',), ('지하철',), ('연락', '문의'))),
            ('zh', '去首尔站的末班车是几点？', (('서울역', '서울 역'), ('막차', '마지막 열차', '마지막 기차', '마지막 지하철'))),
            ('zh', '地铁站有电梯吗？', (('지하철',), ('엘리베이터', '승강기'))),
        ]
        data = preprocess.pd.DataFrame([
            {'번호': index, '질문': question, '날짜': '2026-11-12'}
            for index, (_, question, _) in enumerate(samples, 1)
        ])
        result = preprocess.run(data, translation_enabled=True)
        self.assertEqual(len(result), len(samples))
        for index, (language, question, required_facts) in enumerate(samples):
            row = result.iloc[index]
            translated = row['normalizedForGeneration']
            print(f'[{language}] {question} → {translated}')
            with self.subTest(language=language, question=question):
                self.assertEqual(row['originalText'], question)
                self.assertEqual(json.loads(row['extractedEntities'])['translated_from'], language)
                self.assertRegex(translated, r'[가-힣]')
                for alternatives in required_facts:
                    self.assertTrue(any(term in translated for term in alternatives),
                                    f'Expected one of {alternatives} in {translated!r}')
