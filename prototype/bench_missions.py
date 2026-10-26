"""Synthetic benchmark for match_missions.py.

Simulates the two mission screens (no real screenshots yet):
  list   — event header + 3-5 consecutive missions of one category, names
           wrapped at the game's \n, reward/button noise lines between
  popup  — one mission's description lines (the detail dialog)
with per-character OCR noise. Also checks that CARD screens (passive lines
from index.json) do not get mistaken for mission screens.

Scoring is on the ENGLISH text the user would see, since twins with
identical text are interchangeable for them.
"""

import json
import random
import sys
from pathlib import Path

import match as cardmatch
import match_missions as mm

CARDS = Path(__file__).parent.parent / "android/app/src/main/assets/index.json"


def card_score(lines, cards):
    ranked = cardmatch.rank([(ln, 1.0) for ln in lines], cards)
    return ranked[0][1] if ranked else 0.0

NOISE = ["受け取る", "挑戦する", "龍石×2", "ミッション", "達成済み", "報酬", "ゼニー×10000", "期間限定"]
KANA = "あいうえおかきくけこさしすせそたちつてとなにぬねの"


def garble(s, rate, rng):
    out = []
    for ch in s:
        if rng.random() < rate and not ch.isdigit():
            out.append(rng.choice(KANA))
        else:
            out.append(ch)
    return "".join(out)


def list_screen(index, rng, rate):
    by_cat = {}
    for mid, m in index.missions.items():
        by_cat.setdefault(str(m["c"]), []).append(mid)
    cat = rng.choice([c for c, v in by_cat.items() if len(v) >= 3])
    mids = sorted(by_cat[cat], key=int)
    start = rng.randrange(0, max(1, len(mids) - 4))
    shown = mids[start:start + rng.randint(3, 5)]
    lines = []
    if cat in index.cats and rng.random() < 0.7:
        lines.append(garble(index.cats[cat], rate, rng))
    for mid in shown:
        lines += [garble(x, rate, rng) for x in index.missions[mid]["n"].split("\n")]
        lines += rng.sample(NOISE, 2)
    return lines, shown


def popup_screen(index, rng, rate):
    mid = rng.choice(list(index.missions))
    m = index.missions[mid]
    lines = [garble(x, rate, rng) for x in (m["n"] + "\n" + m["d"]).split("\n")]
    return lines + ["OK"], [mid]


def en(index, mid):
    """Compared on the JP text: twins with identical JP are the same
    instruction even where Global's wording drifts ("cond." vs
    "conditions"), so either is a correct answer for the user."""
    return index.missions[mid]["n"]


def run(kind, n, rate, index, rng, cards):
    tp = fp = total = screens_ok = 0
    for _ in range(n):
        lines, truth = (list_screen if kind == "list" else popup_screen)(index, rng, rate)
        res = mm.match(lines, index)
        want = [en(index, t) for t in truth]
        got = [en(index, mid) for mid, _ in res["missions"]]
        total += len(want)
        tp += sum(1 for g in got if g in want)
        fp += sum(1 for g in got if g not in want)
        screens_ok += mm.is_mission_screen(res, card_score(lines, cards))
    print(f"{kind:5} rate={rate:.2f}: recall {tp}/{total} ({100*tp/total:.0f}%), "
          f"wrong {fp}, detected as missions {screens_ok}/{n}")


def card_screens(n, index, rng, cards):
    recs = [r for k, r in cards.items() if not k.startswith("__") and r.get("lines")]
    false = 0
    for _ in range(n):
        r = rng.choice(recs)
        lines = list(r["lines"]) + [r.get("title") or "", r.get("name") or ""]
        res = mm.match(lines, index)
        false += mm.is_mission_screen(res, card_score(lines, cards))
    print(f"card screens misdetected as missions: {false}/{n}")


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    index = mm.MissionIndex.load()
    cards = cardmatch.load_index(CARDS)
    rng = random.Random(11)
    for rate in (0.0, 0.05, 0.10):
        run("list", n, rate, index, rng, cards)
        run("popup", n, rate, index, rng, cards)
    card_screens(n, index, rng, cards)
