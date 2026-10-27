"""Deterministic keyword-only English policy. No model or third-party imports."""
from dataclasses import dataclass
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import re
import unicodedata

DEFAULT_POLICY_PATH = Path(__file__).resolve().parents[1] / 'dataset/keyword_policy/en_v1.json'
PLACEHOLDERS = re.compile(r'\[(?:TEL|EMAIL|RRN|CARD|URL|METRO_TRAIN|KORAIL_TRAIN)\]', re.I)
KOREAN_TOKEN = re.compile(r'[가-힣]{2,}')
ENGLISH_TOKEN = re.compile(r"[a-z0-9_]+(?:'[a-z]+)*")
# These are the existing Korean/domain lists, unchanged.
KOREAN_STOPWORDS = frozenset({
    '은', '는', '이', '가', '을', '를', '에', '의', '도', '만', '와', '과',
    '로', '으로', '부터', '까지', '에서', '하다', '이다', '있다', '없다',
    '요', '네', '입니다', '해주세요', '주세요', '됩니다', '합니다',
    '어떻게', '왜', '뭐', '무엇', '어디', '언제', '누가', '저', '그',
    '좀', '제발', '진짜', '정말', '그냥', '너무', '한번', '그리고',
    '하시', '하지', '있는', '있나', '있어요', '있습니다',
})
DOMAIN_STOPWORDS = frozenset({
    '지하철', '열차', '역', '또타', '또타24', '챗봇', '민원', '고객',
    '안녕하세요', '안녕', '수고', '감사합니다', '문의', '질문',
})
STOPWORDS = KOREAN_STOPWORDS | DOMAIN_STOPWORDS


def normalize(value):
    return ' '.join(unicodedata.normalize('NFKC', value).casefold().replace('’', "'").split())


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate keyword policy JSON key')
        result[key] = value
    return result


@dataclass(frozen=True)
class KeywordPolicy:
    version: str
    sha256: str
    source_bytes: bytes
    stopwords: frozenset
    protected_tokens: frozenset
    rules: tuple

    @classmethod
    def load(cls, path):
        try:
            raw = Path(path).read_bytes()
            data = json.loads(raw.decode('utf-8'), object_pairs_hook=_unique_object)
            if not isinstance(data, dict) or set(data) != {
                'version', 'stopwords', 'protected_tokens', 'protected_phrases', 'aliases'
            }:
                raise ValueError('Invalid keyword policy fields')
            if not isinstance(data['version'], str) or not data['version'].strip():
                raise ValueError('Invalid keyword policy version')
            def word_list(name):
                words = data[name]
                if not isinstance(words, list) or any(not isinstance(w, str) or not re.fullmatch('[a-z]+', normalize(w)) for w in words):
                    raise ValueError('Invalid keyword policy word list')
                return frozenset(normalize(w) for w in words)
            stopwords = word_list('stopwords')
            protected = word_list('protected_tokens')
            mappings = {}
            for name in ('protected_phrases', 'aliases'):
                if not isinstance(data[name], dict):
                    raise ValueError('Invalid keyword policy mapping')
                for key, value in data[name].items():
                    if not isinstance(value, str):
                        raise ValueError('Invalid keyword policy representative')
                    key, value = normalize(key), normalize(value)
                    if not re.fullmatch(r"[a-z]+(?:[ '-][a-z]+)*", key) or not re.fullmatch(r"[a-z]+(?:[ '-][a-z]+)*", value):
                        raise ValueError('Invalid keyword policy phrase')
                    if key in mappings and mappings[key] != value:
                        raise ValueError('Conflicting normalized keyword policy rules')
                    mappings[key] = value
            def resolve(key, seen):
                target = mappings.get(key, key)
                if target == key:
                    return key
                if target in seen:
                    raise ValueError('Cyclic keyword policy aliases')
                return resolve(target, seen | {target})
            resolved = {key: resolve(key, {key}) for key in mappings}
            # A canonical representative must itself be recognized on later passes.
            resolved.update({value: value for value in tuple(resolved.values())})
            rules = tuple((re.compile(r"(?<![a-z0-9_'])" + r'\s+'.join(re.escape(part) for part in key.split()) + r"(?![a-z0-9_'])"), value)
                          for key, value in sorted(resolved.items(), key=lambda item: (-len(item[0]), item[0])))
            return cls(data['version'], hashlib.sha256(raw).hexdigest(), raw, stopwords, protected, rules)
        except (OSError, ValueError, TypeError, UnicodeError) as exc:
            # Never include file contents or input sentences in diagnostics.
            raise ValueError(f'Keyword policy initialization failed: {Path(path)} ({type(exc).__name__})') from None

    def english_tokens(self, text):
        text = normalize(PLACEHOLDERS.sub(' ', text))
        tokens, excluded, position = [], 0, 0
        contractions = {"can't": ('cannot',), "don't": ('do', 'not'), "isn't": ('is', 'not')}
        while position < len(text):
            matches = [(match, value) for pattern, value in self.rules
                       if (match := pattern.match(text, position)) is not None]
            if matches:
                match, value = max(matches, key=lambda item: item[0].end())
                tokens.append(value)
                position = match.end()
                continue
            match = ENGLISH_TOKEN.match(text, position)
            if match is None:
                position += 1
                continue
            word = match.group()
            position = match.end()
            # Do not extract letter fragments out of mixed identifiers.
            if any(ch.isdigit() or ch == '_' for ch in word):
                continue
            for token in contractions.get(word, (word,)):
                if token in self.stopwords and token not in self.protected_tokens:
                    excluded += 1
                elif len(token) >= 2:
                    tokens.append(token)
        return tokens, excluded

    def tokenize(self, text):
        english, excluded = self.english_tokens(text)
        korean = [word for word in KOREAN_TOKEN.findall(PLACEHOLDERS.sub(' ', text)) if word not in STOPWORDS]
        return korean + english, excluded

    def candidate(self, word):
        if KOREAN_TOKEN.search(word):
            return word if word.strip() else None
        tokens, _ = self.english_tokens(word)
        return tokens[0] if len(tokens) == 1 else None


@lru_cache(maxsize=None)
def _load_policy(path):
    return KeywordPolicy.load(path)


def get_policy():
    # The worker supplies a validated per-job snapshot; default is an app resource.
    return _load_policy(str(Path(os.environ.get('TTOBAGI_KEYWORD_POLICY_PATH') or DEFAULT_POLICY_PATH).resolve()))


def write_audit(path, policy, excluded, keywords):
    Path(path).write_text(json.dumps({
        'policyVersion': policy.version, 'policySha256': policy.sha256,
        'excludedEnglishTokenOccurrences': excluded,
        'emptyKeywordClusterCount': sum(not words for words in keywords.values()),
    }, ensure_ascii=False, indent=2), encoding='utf-8')
