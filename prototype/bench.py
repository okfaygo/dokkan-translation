"""Fast benchmark harness for the matcher: vectorized re-implementation of
match.rank (identical math, rapidfuzz.process.cdist + numpy instead of a
Python loop) plus synthetic card-page input generators.

Usage:  python bench.py            # run all benchmarks
"""

import json
import random
from collections import defaultdict

import numpy as np
from rapidfuzz import fuzz, process

import match as M

EL_KANJI = {0: "速", 1: "技", 2: "知", 3: "力", 4: "体"}
RAR = {3: "SSR", 4: "UR", 5: "LR"}


class FastMatcher:
    """Same scores as match.rank, ~100x faster for batch runs."""

    def __init__(self, index):
        self.index = index
        self.ids = list(index.keys())
        self.owner, self.keys = [], []
        for i, cid in enumerate(self.ids):
            for k in M._keys(index[cid]):
                self.owner.append(i)
                self.keys.append(k)
        self.owner = np.array(self.owner)
        self.klen = np.array([len(k) for k in self.keys], dtype=np.float64)
        idf = M.key_idf(index)
        self.kidf = np.array([idf.get(k, 1.0) for k in self.keys])
        self.rarity = np.array([index[c].get("rarity") or 0 for c in self.ids])
        self.element = np.array(
            [int(index[c].get("element") or -1) % 10 if index[c].get("element")
             else -1 for c in self.ids])

    def rank(self, lines, threshold=70.0, penalty=None, use_idf=True):
        penalty = M.HINT_PENALTY if penalty is None else penalty
        el, rar = M.extract_hints(lines)
        total = np.zeros(len(self.ids))
        for text in lines:
            text = text.strip()
            if len(text) < 4:
                continue
            w = min(len(text), 24) / 24
            r = process.cdist([text], self.keys, scorer=fuzz.ratio,
                              workers=-1)[0]
            m = float(len(text))
            if m >= M.CONTAIN_MIN_LEN:
                coverage = np.minimum(r * (m + self.klen) / (2 * m), 100.0)
                r = np.where(self.klen > m,
                             np.maximum(r, coverage * 0.95), r)
            # raw similarity gates; idf decides which match is informative
            val = np.where(r >= threshold, r * (self.kidf if use_idf else 1.0), 0.0)
            best = np.zeros(len(self.ids))
            np.maximum.at(best, self.owner, val)
            total += best * w
        if el is not None:
            total *= np.where(self.element == el, M.HINT_BOOST, penalty)
        if rar is not None:
            total *= np.where(self.rarity == rar, M.HINT_BOOST, penalty)

        order = np.argsort(-total)
        ranked = [(self.ids[i], total[i]) for i in order if total[i] > 0][:50]
        if not ranked:
            return ranked
        cutoff = ranked[0][1] * M.TIE_MARGIN
        head = [x for x in ranked if x[1] >= cutoff]
        head.sort(key=lambda kv: (int(kv[0]) >= 4_000_000,
                                  -self.index[kv[0]].get("rarity", 0),
                                  -int(kv[0])))
        return head + ranked[len(head):]


def is_correct(index, top, expect):
    if top == expect:
        return True
    a, b = index[top], index[expect]
    return (a["name"], a["title"], a.get("rarity")) == \
           (b["name"], b["title"], b.get("rarity"))


def badge_lines(rec, mode):
    if mode == "none":
        return []
    el = int(rec.get("element") or 0)
    if mode == "correct":
        out = [("極" if el >= 20 else "超") + EL_KANJI[el % 10]]
        if rec.get("rarity") in RAR:
            out.append(RAR[rec["rarity"]])
    else:  # wrong: both badges misread as other valid badges
        out = [("極" if el >= 20 else "超") + EL_KANJI[(el + 1) % 5],
               "LR" if rec.get("rarity") != 5 else "UR"]
    return out


def main():
    index = M.load_index()
    fm = FastMatcher(index)
    rng = random.Random(11)

    def noisy(s):
        if len(s) > 6 and rng.random() < 0.7:
            i = rng.randrange(len(s))
            s = s[:i] + s[i + 1:]
        return s

    def page_input(rec, mode):
        lines = []
        fl = (rec.get("leader") or "").replace("\n", "")
        if fl:
            lines.append(noisy(fl[:21]))
        if rec.get("sa_names"):
            lines.append(noisy(rec["sa_names"][-1]))
        if rec.get("name"):
            lines.append(noisy(rec["name"].replace("\n", "")[:7]))
        if rec.get("title"):
            lines.append(noisy(rec["title"][:8]))
        if rec.get("passive_name"):
            lines.append(noisy(rec["passive_name"]))
        lines += badge_lines(rec, mode)
        lines += ["リーダースキル", "必殺技Lv", "パッシブスキル", "カテゴリ",
                  "詳細一覧", "超サイヤ人", "フルパワー", "ドラゴンボールを求めし者"]
        return lines

    pool = [(c, r) for c, r in index.items()
            if int(c) < 4_000_000 and r.get("leader")
            and r.get("rarity", 0) >= 3]
    sample = rng.sample(pool, 150)

    print("=== general card-page benchmark (150 cards) ===")
    print("idf     none   correct   MISREAD-both")
    for use_idf in (False, True):
        row = []
        for mode in ("none", "correct", "wrong"):
            rng.seed(11)
            ok = sum(1 for cid, rec in sample
                     if (rk := fm.rank(page_input(rec, mode), use_idf=use_idf))
                     and is_correct(index, rk[0][0], cid))
            row.append(ok)
        print(f"  {str(use_idf):5}  {row[0]:3}     {row[1]:3}       {row[2]:3}")

    # NAME-ONLY degenerate case: the reported failure shape
    print("\n=== name-only input (stylized text unread) — 60 cards ===")
    from collections import defaultdict as _dd
    gname = _dd(list)
    for cid, r in index.items():
        if int(cid) < 4_000_000 and r.get("name"):
            gname[r["name"]].append(cid)
    rng.seed(5)
    big = [ids for ids in gname.values() if len(ids) >= 6]
    picks = [rng.choice(ids) for ids in rng.sample(big, 60)]
    print("idf     top1   in-top4   in-top8   tied@top")
    for use_idf in (False, True):
        t1 = t4 = t8 = 0
        tied = []
        for cid in picks:
            rec = index[cid]
            lines = [rec["name"], "リーダースキル", "必殺技Lv", "カテゴリ",
                     "詳細一覧", "超サイヤ人"]
            rk = fm.rank(lines, use_idf=use_idf)
            if rk and is_correct(index, rk[0][0], cid):
                t1 += 1
            if any(is_correct(index, c, cid) for c, _ in rk[:4]):
                t4 += 1
            if any(is_correct(index, c, cid) for c, _ in rk[:8]):
                t8 += 1
            if rk:
                tied.append(sum(1 for _, s in rk if s >= rk[0][1] * M.TIE_MARGIN))
        print(f"  {str(use_idf):5}  {t1:3}    {t4:3}       {t8:3}      "
              f"median {sorted(tied)[len(tied)//2]}")

    # hostile same-character benchmark
    def heavy_noise(s):
        out = []
        for ch in s:
            r = rng.random()
            if r < 0.08:
                continue
            if r < 0.13:
                out.append(rng.choice("Tら〇S1己d"))
            else:
                out.append(ch)
        return "".join(out)

    def hostile(rec, mode):
        lines = []
        fl = (rec.get("leader") or "").replace("\n", "")
        if fl:
            lines.append(heavy_noise(fl[:14]))
        if rec.get("sa_names"):
            lines.append(heavy_noise(rec["sa_names"][0]))
        if rec.get("name"):
            lines.append(heavy_noise(rec["name"].replace("\n", "")[:5]))
        lines += badge_lines(rec, mode)
        lines += ["リーダースキル", "必殺技Lv", "カテゴリ", "超サイヤ人", "フルパワー"]
        return lines

    groups = defaultdict(list)
    for cid, r in index.items():
        if int(cid) < 4_000_000 and r.get("name") and r.get("leader"):
            groups[r["name"]].append(cid)
    rng.seed(21)
    targets = [rng.choice(ids) for name, ids in groups.items()
               if len(ids) >= 2 and
               (len({int(index[c].get("element") or -1) % 10
                     for c in ids}) > 1 or
                len({index[c].get("rarity") for c in ids}) > 1)]
    hs = rng.sample(targets, 120)

    print("\n=== hostile same-character benchmark (120 cards) ===")
    print("penalty   no-badge-top1   badge-top1   badge-top4")
    for pen in (0.88, 1.0):
        r0 = r1 = r4 = 0
        for cid in hs:
            rng.seed(hash(cid) & 0xFFFF)
            a = fm.rank(hostile(index[cid], "none"), penalty=pen)
            rng.seed(hash(cid) & 0xFFFF)
            b = fm.rank(hostile(index[cid], "correct"), penalty=pen)
            if a and is_correct(index, a[0][0], cid):
                r0 += 1
            if b and is_correct(index, b[0][0], cid):
                r1 += 1
            if any(is_correct(index, c, cid) for c, _ in b[:4]):
                r4 += 1
        print(f"  {pen:4}       {r0:3}           {r1:3}         {r4:3}")


if __name__ == "__main__":
    main()
