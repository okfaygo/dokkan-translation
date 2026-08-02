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


def rank(candidates, index, threshold=70):
    """candidates: [(text, ocr_conf)]; returns [(card_id, total_score)]."""
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
