"""Korean text translation through the Azure Translator REST API."""

import json
import os
import re
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class TranslationError(RuntimeError):
    """A translation could not be completed."""


# In English transit questions, taking a bike on board means bringing it along.
_TAKE_BIKE_ON_TRANSIT = re.compile(
    r'\btake\s+((?:(?:a|my|your|the)\s+)?(?:bicycle|bike))\s+on\s+'
    r'((?:the\s+|a\s+)?(?:subway|metro|train))\b',
    re.IGNORECASE,
)


def translate_with_azure(text: str) -> str:
    key = os.environ.get('TTOBAGI_AZURE_TRANSLATOR_KEY', '').strip()
    if not key:
        raise TranslationError('Azure Translator is not configured: set TTOBAGI_AZURE_TRANSLATOR_KEY')

    headers = {'Content-Type': 'application/json; charset=utf-8', 'Ocp-Apim-Subscription-Key': key}
    region = os.environ.get('TTOBAGI_AZURE_TRANSLATOR_REGION', '').strip()
    if region:
        headers['Ocp-Apim-Subscription-Region'] = region
    translation_input = _TAKE_BIKE_ON_TRANSIT.sub(r'bring \1 aboard \2', text)
    request = Request(
        'https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=ko',
        data=json.dumps([{'Text': translation_input}], ensure_ascii=False).encode('utf-8'),
        headers=headers,
        method='POST',
    )

    for attempt in range(3):
        delay = 2 ** attempt
        try:
            with urlopen(request, timeout=15) as response:
                result = json.load(response)[0]['translations'][0]['text']
            if not isinstance(result, str) or not result.strip():
                raise TranslationError('Azure Translator returned an empty or invalid translation')
            return result.strip()
        except HTTPError as exc:
            status = exc.code
            retry_after = exc.headers.get('Retry-After', '') if exc.headers else ''
            exc.close()
            if retry_after.isascii() and retry_after.isdecimal():
                delay = max(delay, int(retry_after))
            if (status not in (408, 429, 500, 502, 503, 504)
                    or attempt == 2 or delay > 30):
                raise TranslationError(f'Azure Translator returned HTTP {status}') from None
        except (URLError, TimeoutError):
            if attempt == 2:
                raise TranslationError('Azure Translator connection failed or timed out') from None
        except (ValueError, TypeError, KeyError, IndexError):
            raise TranslationError('Azure Translator returned an invalid response') from None
        time.sleep(delay)
