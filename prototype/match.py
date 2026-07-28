"""Fuzzy-match OCR candidates against a JP-title -> card-id index.

The index (index.json) maps Japanese card titles (and optionally names) to
dokkan card ids: {"サイヤ人の一撃!": 1021501, ...}

OCR output is noisy, so we score every candidate line against every index
key with rapidfuzz and return the best (card_id, score) above a threshold.

Building the full index is a one-time scrape (JP titles appear on the
fandom wiki card pages and in the game's own database); see NOTES.md.
"""

import json

from rapidfuzz import fuzz, process


def load_index(path="index.json"):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def best_match(candidates, index, threshold=70):
    """candidates: [(text, ocr_conf)]; returns (card_id, title, score) or None."""
    best = None
    for text, _conf in candidates:
        hit = process.extractOne(text, index.keys(), scorer=fuzz.ratio)
        if hit and hit[1] >= threshold:
            if best is None or hit[1] > best[2]:
                best = (index[hit[0]], hit[0], hit[1])
    return best


if __name__ == "__main__":
    import sys

    candidates = [(line, 0) for line in sys.argv[1:]]
    index = load_index()
    print(best_match(candidates, index))
