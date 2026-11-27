"""Filter automatically suggested FAQ tags before they reach operator review."""
import re
import unicodedata

from services.keyword_policy import PLACEHOLDERS, get_policy, normalize


_LATIN_FRAGMENT = {'lost', 'and', 'found', 'de', 'th', 'la', 'froid', 'climatisation'}
_ENGLISH_TERMS = {'lost and found', 'cctv', 'k pass', 'gtx', 'srt', 'qr', 'nfc'}
_UTTERANCE_END = re.compile(
    r'(?:[가-힣]*(?:나요|까요|습니까|세요|해주셈|해줘|해요|있어요|없어요|잇음|앉아있음)|'
    r'뭐야|왜\s+이거\s+해)$'
)
_TAG_CHARS = re.compile(r"[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9][가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9 &'’/-]*")


def clean_tag(text, *, policy=None):
    if not isinstance(text, str) or PLACEHOLDERS.search(text):
        return None
    term = ' '.join(text.strip().split())
    value = unicodedata.normalize('NFKC', term)
    if not 2 <= len(value) <= 100 or not _TAG_CHARS.fullmatch(value):
        return None
    if not any(char.isalpha() for char in value) or _UTTERANCE_END.search(value):
        return None
    if not re.search(r'[가-힣ㄱ-ㅎㅏ-ㅣ]', value):
        policy = policy or get_policy()
        folded = normalize(value)
        if folded in _LATIN_FRAGMENT:
            return None
        if (folded not in _ENGLISH_TERMS and folded not in policy.protected_tokens
                and not any(pattern.fullmatch(folded) for pattern, _ in policy.rules)):
            return None
    return term


def clean_keywords(words, *, policy):
    tags = (clean_tag(word, policy=policy) for word in words)
    unique = {}
    for tag in tags:
        if tag is not None:
            unique.setdefault(normalize(tag), tag)
    return list(unique.values())
