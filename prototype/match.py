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
    for k in (rec.get("title"), rec.get("name")):
        if k:
            keys.append(k)
    return keys


def rank(candidates, index, threshold=70):
    """candidates: [(text, ocr_conf)]; returns [(card_id, total_score)]."""
    scores = {}
    for text, _conf in candidates:
        if not text.strip():
            continue
        for cid, rec in index.items():
            best = max((fuzz.ratio(text, k) for k in _keys(rec)), default=0)
            if best >= threshold:
                scores[cid] = scores.get(cid, 0) + best
    return sorted(scores.items(), key=lambda kv: -kv[1])


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
