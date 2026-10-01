"""Reference mission matcher; android/.../match/MissionMatcher.kt mirrors it.

A mission screen differs from a card screen in the way that matters here:
it shows SEVERAL missions at once, so the answer is a list, not a winner.

  1. Segments: every OCR line, plus every pair of adjacent lines joined —
     mission names wrap onto two lines exactly where the JP text has its
     \\n, and the second half ("を3回クリア") is useless alone.
  2. Each segment is fuzzy-matched (fuzz.ratio >= THRESHOLD) against the
     key strings: full names, full descriptions, their individual lines,
     and event (category) names.
  3. Digits must agree. Templated missions differ ONLY in their numbers
     ("ステージ1を1回クリア" vs "ステージ2を1回クリア" score ~90 against
     each other), and plain UI text OCRs digits reliably — the icon-badge
     digit problem from the card screens does not apply here.
  4. Event vote: segments vote for categories (incl. the event name in the
     screen header). Identical names are common — 402 missions are named
     "clear the specified Extreme Z-Battle under special conditions" — and
     the winning event is what picks the right one.
  5. Greedy: best-scoring (segment, mission) pairs first, each OCR line used
     at most once, so a joined pair beats its own halves.

A segment that still maps to several missions with DIFFERENT English names
is dropped rather than guessed. A mission picked without event support is
returned with confident=False: its (generic) name is right, but its
description could belong to another event's twin.
"""

import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from rapidfuzz import fuzz

THRESHOLD = 82.0
DIGIT_PENALTY = 20.0
TIE_BAND = 3.0
MIN_SEGMENT = 5
MIN_PART = 6          # shortest individual name/description line used as a key
MIN_LINES_FOR_MISSIONS = 2
# an unreadable line inside a description must not split it in two
MAX_GAP = 1
# least text a mission must explain to be reported (~8 well-read chars)
MIN_VALUE = THRESHOLD * 8

DIGITS_RE = re.compile(r"\d+")
DEFAULT_TABLE = (Path(__file__).parent.parent /
                 "android/app/src/main/assets/missions.json")


def digits(s):
    return DIGITS_RE.findall(s)


class MissionIndex:
    def __init__(self, table):
        self.missions = {k: v for k, v in table.items() if not k.startswith("__")}
        self.cats = table.get("__cats__", {})
        # key string -> list of (mission id or None, category id)
        self.keys = defaultdict(list)
        # keys that span several screen lines: the only ones a joined pair
        # of OCR lines is compared against
        self.multiline = set()
        for mid, m in self.missions.items():
            cat = str(m["c"])
            for text in (m["n"], m["d"]):
                if not text:
                    continue
                joined = text.replace("\n", "")
                self.keys[joined].append((mid, cat))
                if "\n" in text:
                    self.multiline.add(joined)
                for part in text.split("\n"):
                    if len(part) >= MIN_PART:
                        self.keys[part].append((mid, cat))
        for cat, name in self.cats.items():
            self.keys[name].append((None, cat))
            # the in-game banner shows the event name without the 【tag】
            tail = name.split("】", 1)[-1].strip()
            if tail != name and len(tail) >= MIN_PART:
                self.keys[tail].append((None, cat))
        for k in self.keys:
            self.keys[k] = list(dict.fromkeys(self.keys[k]))
        self.key_list = list(self.keys)
        # Ties go to the NEWEST: reruns of an event repeat its missions word
        # for word, and the player is looking at the one running now.
        self.cat_newest = defaultdict(int)
        for mid, m in self.missions.items():
            cat = str(m["c"])
            self.cat_newest[cat] = max(self.cat_newest[cat], int(mid))
        self.multiline_list = [k for k in self.key_list if k in self.multiline]

    @classmethod
    def load(cls, path=DEFAULT_TABLE):
        return cls(json.loads(Path(path).read_text(encoding="utf-8")))


def key_score(seg, key):
    r = fuzz.ratio(seg, key)
    if r >= THRESHOLD and digits(seg) != digits(key):
        r -= DIGIT_PENALTY
    return r


def segments(lines):
    lines = [ln.strip() for ln in lines]
    out = []
    for i, ln in enumerate(lines):
        if len(ln) >= MIN_SEGMENT:
            out.append(((i,), ln))
        if i + 1 < len(lines) and lines[i + 1]:
            joined = ln + lines[i + 1]
            if len(joined) >= MIN_SEGMENT:
                out.append(((i, i + 1), joined))
    return out


def claim(evidence, used):
    """(value, lines) of the best CONTIGUOUS block of a mission's evidence.

    One mission's text sits in one place on screen. Without this, a
    mission could take the first name line of one list entry and the
    second line of another ("フリーザの" + another entry's
    "極限Z覚醒を1段階行おう") and outscore both real entries.
    """
    free = [e for e in evidence if not used & set(e[0])]
    free.sort(key=lambda e: (e[0][0], -e[1]))
    best = None
    block = []
    for item in free + [None]:
        if item is not None and block and item[0][0] <= max(block_lines(block)) + 1 + MAX_GAP:
            block.append(item)
            continue
        if block:
            taken, total, card_scale = set(), 0.0, 0.0
            for span, v, cv in sorted(block, key=lambda e: -e[1]):
                if taken & set(span):
                    continue
                taken |= set(span)
                total += v
                card_scale += cv
            if best is None or total > best[0]:
                best = (total, taken, card_scale)
        block = [item] if item is not None else []
    return best


def block_lines(block):
    return {i for e in block for i in e[0]}


def match(lines, index):
    """-> {"missions": [(mission id, confident)], "used_lines": n, ...}"""
    segs = segments(lines)
    # per segment: {(mid, cat): best score}
    hits = []
    for span, seg in segs:
        best = {}
        # A pair is only evidence for text that really spans two lines.
        # Against a one-line key, "挑戦する" + "セル(完全体)(GT)の" still
        # scores ~83 — a button label glued onto a name.
        for key in (index.key_list if len(span) == 1 else index.multiline_list):
            # ratio can't reach the threshold across this length gap
            if abs(len(key) - len(seg)) * 100 > (100 - THRESHOLD) * (len(key) + len(seg)):
                continue
            r = key_score(seg, key)
            if r < THRESHOLD:
                continue
            for ref in index.keys[key]:
                if r > best.get(ref, 0):
                    best[ref] = r
        if best:
            hits.append((span, seg, best))

    # event vote: single lines only, so a joined pair doesn't count twice
    votes = defaultdict(float)
    for span, seg, best in hits:
        if len(span) != 1:
            continue
        per_cat = {}
        for (mid, cat), r in best.items():
            per_cat[cat] = max(per_cat.get(cat, 0), r)
        weight = min(len(seg), 24) / 24
        for cat, r in per_cat.items():
            # a key shared by many events says little about which one
            votes[cat] += r * weight / len(per_cat)
    top_cat = (max(votes, key=lambda c: (votes[c], index.cat_newest[c], int(c)))
               if votes else None)

    # Evidence per mission: (lines, value) for every segment it matched.
    evidence = defaultdict(list)
    for span, seg, best in hits:
        for (mid, cat), r in best.items():
            if mid is not None:
                evidence[mid].append((span, r * min(len(seg), 40),
                                      r * min(len(seg), 24) / 24))

    # Greedy cover: the mission explaining the most still-unexplained text
    # wins and claims those lines; repeat. A detail popup's lines all point
    # at one mission, while each shared line ("チャレンジイベント") points
    # at thousands, so the real one wins by explaining all of them at once.
    used = set()
    explained = set()
    chosen = {}
    score = 0.0
    while True:
        values = {}
        claims = {}
        scales = {}
        for mid, ev in evidence.items():
            if mid in chosen:
                continue
            best_block = claim(ev, used)
            if best_block:
                values[mid], claims[mid], scales[mid] = best_block
        if not values:
            break
        top = max(values.values())
        if top < MIN_VALUE:
            break
        near = [mid for mid, v in values.items() if v >= top * (1 - TIE_BAND / 100)]
        in_cat = [mid for mid in near if str(index.missions[mid]["c"]) == top_cat]
        pool = in_cat or near
        pick = max(pool, key=lambda mid: (values[mid], int(mid)))
        used |= claims[pick]
        if not in_cat and len(near) > 1:
            # No event to tell twins apart. Identical JP text means the
            # same instruction (Global's wording drifts: "special cond." vs
            # "special conditions"), so show the most common English; if
            # the JP texts differ, these are different missions — skip.
            if len({index.missions[mid]["n"] for mid in near}) > 1:
                continue
            english = Counter(index.missions[mid]["en"] for mid in near
                              if index.missions[mid]["en"])
            if english:
                top_en = english.most_common(1)[0][0]
                pick = max((mid for mid in near
                            if index.missions[mid]["en"] == top_en), key=int)
        confident = bool(in_cat) or len(near) == 1
        chosen[pick] = (min(claims[pick]), confident)
        score += scales[pick]
        explained |= claims[pick]

    ordered = sorted(chosen.items(), key=lambda kv: kv[1][0])
    return {
        "missions": [(mid, conf) for mid, (_, conf) in ordered],
        "used_lines": len(explained),
        # on the card matcher's scale (ratio x min(len, 24)/24 per line),
        # so the two readings of a screenshot can be compared directly
        "score": score,
        "top_cat": top_cat,
    }


def is_mission_screen(result, card_score):
    """Missions win only if they explain more of the screen than the best
    card does. Mission descriptions quote card names and categories
    ("「地球を守りし英雄」カテゴリの"), so a card page always explains SOME
    mission text — but its own card explains far more."""
    return (result["used_lines"] >= MIN_LINES_FOR_MISSIONS
            and result["score"] > card_score)
