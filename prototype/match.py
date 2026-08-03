"""Fuzzy-match OCR candidates against the scraped card index.

index.json (built by build_index.py) maps card ids to records:
    {"1032261": {"name": ..., "title": ..., "lines": [...],
                 "pre_eza_lines": [...], ...}, ...}

OCR output is noisy, so we vote: every candidate line contributes its best
rapidfuzz score (>= threshold) to each card it resembles; the card with the
highest total wins. Robust to digit-level OCR errors and generic lines that
several kits share — only the right card accumulates score across ALL lines.
"""

import json
import math
from collections import Counter

from rapidfuzz import fuzz


def load_index(path="index.json"):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _keys(rec):
    keys = list(rec.get("lines", []))
    keys += rec.get("pre_eza_lines", [])
    keys += rec.get("active_lines", [])
    keys += rec.get("sa_names", [])
    # leader-skill text: the one plain-font element on the card page itself,
    # so it makes card-page screenshots identifiable, not just passive popups.
    # The unwrapped full text is included too: the in-game card page shows
    # the leader as ONE truncated line, which containment scoring matches
    # against the full string regardless of wrap points.
    for field in ("leader", "pre_eza_leader"):
        text = rec.get(field)
        if text:
            keys += [l.strip("、 　") for l in text.splitlines() if l.strip()]
            keys.append(text.replace("\n", ""))
    for k in (rec.get("title"), rec.get("name"),
              rec.get("passive_name"), rec.get("active_name")):
        if k:
            keys.append(k)
    return keys


def _weight(text):
    """Longer lines are more card-specific; short fragments (category names,
    UI labels, OCR shrapnel) shouldn't outvote a full passive sentence."""
    return min(len(text), 24) / 24


CONTAIN_MIN_LEN = 14

# How many cards share a key before it counts as "generic". Used ONLY to
# judge confidence, never to score.
#
# IDF-style score weighting was tried here and REJECTED on benchmark data
# (both textbook log(N/n) and a flat-below-threshold variant): damping a
# shared name lowers the true signal while junk lines that happen to match
# some card's rare key keep full weight, so noise wins relatively. Every
# arm got worse — name-only top-4 19->16, general misread 141->133.
COMMON_AT = 10
_IDF_CACHE = {}


def _idf_weight(n):
    if n < COMMON_AT:
        return 1.0
    return 1.0 / (1.0 + math.log(n / COMMON_AT))


def key_idf(index):
    """key string -> weight in (0, 1]. Cached per index object."""
    cached = _IDF_CACHE.get(id(index))
    if cached is not None:
        return cached
    counts = Counter()
    for rec in index.values():
        for k in set(_keys(rec)):
            counts[k] += 1
    idf = {k: _idf_weight(n) for k, n in counts.items()}
    _IDF_CACHE[id(index)] = idf
    return idf


def _score(text, key):
    """Similarity of an OCR line to an index key.

    Base: fuzz.ratio (normalized indel). For long-enough lines against
    longer keys, also try CONTAINMENT — how much of the OCR line appears
    in-order inside the key — at a 0.95 discount. This is what makes the
    card page's truncated single-line leader/SA text matchable; the length
    floor keeps short category chips from lighting up every kit that
    mentions them.

    fuzz.ratio == 200*LCS/(m+n), so coverage derives with no extra work:
    LCS/m*100 == ratio*(m+n)/(2m).
    """
    r = fuzz.ratio(text, key)
    if len(text) >= CONTAIN_MIN_LEN and len(key) > len(text):
        coverage = min(r * (len(text) + len(key)) / (2 * len(text)), 100.0)
        r = max(r, coverage * 0.95)
    return r


TIE_MARGIN = 0.98

# The card page shows the card's type badge (超知/極力 etc.) and rarity
# emblem (UR/LR) — 1-3 char OCR lines that never vote (too short) but
# disambiguate same-character cards of different type/rarity. BOOST-ONLY
# (penalty 1.0): benchmarked, a mismatch penalty scores slightly better
# when badges read correctly but collapses (150-case: 148 vs 108) when
# both badges misread — and tiny stylized badges will misread in the wild.
ELEMENT_KANJI = {"速": 0, "技": 1, "知": 2, "力": 3, "体": 4}
RARITY_MARKERS = {"UR": 4, "LR": 5, "SSR": 3}
HINT_BOOST = 1.12
HINT_PENALTY = 1.0


def extract_hints(lines):
    """(element_type or None, rarity or None) from badge-like OCR lines.
    Only the unambiguous forms count: 超X/極X for type, exact UR/LR/SSR."""
    el = rar = None
    for raw in lines:
        s = raw.strip()
        if s.upper() in RARITY_MARKERS:
            rar = RARITY_MARKERS[s.upper()]
        if len(s) == 2 and s[0] in "超極" and s[1] in ELEMENT_KANJI:
            el = ELEMENT_KANJI[s[1]]
    return el, rar


def rank(candidates, index, threshold=70):
    """candidates: [(text, ocr_conf)]; returns [(card_id, total_score)]."""
    el_hint, rar_hint = extract_hints(t for t, _ in candidates)
    scores = {}
    for text, _conf in candidates:
        text = text.strip()
        if len(text) < 4:
            continue
        w = _weight(text)
        for cid, rec in index.items():
            best = max((_score(text, k) for k in _keys(rec)), default=0)
            if best >= threshold:
                scores[cid] = scores.get(cid, 0) + best * w

    for cid in scores:
        rec = index[cid]
        mult = 1.0
        if el_hint is not None and rec.get("element") is not None:
            mult *= (HINT_BOOST if int(rec["element"]) % 10 == el_hint
                     else HINT_PENALTY)
        if rar_hint is not None and rec.get("rarity") is not None:
            mult *= HINT_BOOST if rec["rarity"] == rar_hint else HINT_PENALTY
        scores[cid] *= mult
    ranked = sorted(scores.items(), key=lambda kv: -kv[1])
    if not ranked:
        return ranked
    # Awakening siblings share (nearly) identical text; within the head
    # group prefer base summonable cards over transformed/story forms
    # (4xxxxxxx/9xxxxxxx), then the later stage (higher rarity, higher id)
    cutoff = ranked[0][1] * TIE_MARGIN
    head = [r for r in ranked if r[1] >= cutoff]
    head.sort(key=lambda kv: (int(kv[0]) >= 4_000_000,
                              -index[kv[0]].get("rarity", 0), -int(kv[0])))
    return head + ranked[len(head):]


def best_match(candidates, index, threshold=70):
    """Returns (card_id, record, total_score) or None."""
    ranked = rank(candidates, index, threshold)
    if not ranked:
        return None
    cid, score = ranked[0]
    return cid, index[cid], score


if __name__ == "__main__":
    import sys

    candidates = [(line, 0) for line in sys.argv[1:]]
    index = load_index()
    for cid, score in rank(candidates, index)[:5]:
        rec = index[cid]
        print(f"{score:6.0f}  {cid}  {rec['title']} / {rec['name']}")
