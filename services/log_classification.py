"""Classify observed chatbot outcomes from an explicit label or a percent score."""
from decimal import Decimal
import re


LOG_TYPES = {'CORRECT': 'correct', 'LOW_QUALITY': 'lowQuality', 'UNANSWER': 'unanswer'}
PERCENT_COLUMN = 'keywordMatchPercent'
_DECIMAL = re.compile(r'[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)')


def parse_percent(value):
    """A 0..100 percent, optionally with %, never an inferred 0..1 ratio."""
    text = str(value).strip().removesuffix('%').strip()
    if not _DECIMAL.fullmatch(text):
        raise ValueError('keywordMatchPercent must be a decimal percent between 0 and 100')
    number = Decimal(text)
    if not 0 <= number <= 100:
        raise ValueError('keywordMatchPercent must be between 0 and 100')
    return number


def classify_percent(value):
    number = parse_percent(value)
    if number < 25:
        return 'UNANSWER'
    if number < 75:
        return 'LOW_QUALITY'
    return 'CORRECT'


def raw_match_percent(value):
    """chatting_log stores hundredths of a percent: 9999 means 99.99%."""
    text = str(value).strip()
    if not re.fullmatch(r'[0-9]+', text):
        raise ValueError('ai_percent must be an integer between 0 and 10000')
    number = Decimal(text)
    if number > 10000:
        raise ValueError('ai_percent must be an integer between 0 and 10000')
    return number / 100


def log_type(row):
    # Preserve supplied outcomes. Blank labels may use the explicit percent.
    label = row.get('logType')
    if label is not None and label != '':
        value = label.strip()
        if value not in LOG_TYPES:
            raise ValueError('logType must be CORRECT, LOW_QUALITY or UNANSWER')
        return value
    if PERCENT_COLUMN in row:
        return classify_percent(row[PERCENT_COLUMN])
    if 'ai_percent' in row:
        return classify_percent(format(raw_match_percent(row['ai_percent']), 'f'))
    # Legacy unanswered-learning uploads have neither an outcome nor a score.
    # Training labels (답변확률/질의 유효 여부) are not observed outcomes.
    return 'UNANSWER'
