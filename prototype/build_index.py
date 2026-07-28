"""Build the JP-text -> card-id index by scraping DokkanInfo's JP pages.

DokkanInfo server-embeds full card data as HTML-entity-escaped JSON:
  - https://jpnja.dokkaninfo.com/cards?sort=open_at  -> cardsjson="..."
    one page listing every card (id, JP name, rarity, eza, open_at)
  - https://jpnja.dokkaninfo.com/cards/<id>          -> datajson="..."
    full JP kit: card, leader_skill (name == card title), passive_skill
    (itemized_description == the passive-detail screen lines we OCR),
    active_skill, transformations

Needs a real browser User-Agent (default UA -> Cloudflare 403).

Usage:
    python build_index.py --ids 1032261 4031657     # specific cards
    python build_index.py --sample 20               # stratified validation set
    python build_index.py --all [--min-rarity 3]    # full scrape (hours)
    python build_index.py --rebuild                 # reparse cache, no network

Output: index.json  {card_id: {name, title, leader, passive_name, lines, ...}}
Cache:  cache/list.json, cache/cards/<id>.json.gz (raw datajson, reusable)
"""

import argparse
import gzip
import html
import json
import random
import re
import sys
import time
from pathlib import Path

import requests

BASE = "https://jpnja.dokkaninfo.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
HERE = Path(__file__).parent
CACHE = HERE / "cache"
CARD_CACHE = CACHE / "cards"

JP_RE = re.compile(r"[぀-ヿ一-鿿]")


def _session():
    s = requests.Session()
    s.headers["User-Agent"] = UA
    return s


def _get(session, url, tries=4):
    for attempt in range(tries):
        r = session.get(url, timeout=30)
        if r.status_code in (403, 429, 502, 503) and attempt < tries - 1:
            wait = 5 * (attempt + 1)
            print(f"  HTTP {r.status_code}, backing off {wait}s", file=sys.stderr)
            time.sleep(wait)
            continue
        r.raise_for_status()
        return r.text
    raise RuntimeError(f"gave up on {url}")


def _embedded_json(html_text, attr):
    m = re.search(rf'{attr}="([^"]*)"', html_text)
    if not m:
        raise ValueError(f'no {attr}= attribute found')
    return json.loads(html.unescape(m.group(1)))


def fetch_list(session, refresh=False):
    """All cards (id, JP name, rarity, eza, open_at) from the list page."""
    CACHE.mkdir(exist_ok=True)
    cache_file = CACHE / "list.json"
    if cache_file.exists() and not refresh:
        return json.loads(cache_file.read_text(encoding="utf-8"))
    print("fetching card list (~12MB)...")
    text = _get(session, f"{BASE}/cards?sort=open_at")
    cards = _embedded_json(text, "cardsjson")
    keep = ["id", "name", "rarity", "eza", "open_at", "element", "lv_max"]
    cards = [{k: c.get(k) for k in keep} for c in cards]
    cache_file.write_text(json.dumps(cards, ensure_ascii=False),
                          encoding="utf-8")
    print(f"  {len(cards)} cards cached")
    return cards


def fetch_card(session, card_id, delay=1.0, refresh=False, pre_eza=False):
    """Raw datajson dict for one card, cached gzipped on disk.

    pre_eza=True fetches ?eza=true, which (counterintuitively) serves the
    ORIGINAL pre-EZA kit; the bare URL serves the current max-EZA state.
    """
    CARD_CACHE.mkdir(parents=True, exist_ok=True)
    suffix = ".pre_eza.json.gz" if pre_eza else ".json.gz"
    cache_file = CARD_CACHE / f"{card_id}{suffix}"
    if cache_file.exists() and not refresh:
        with gzip.open(cache_file, "rt", encoding="utf-8") as f:
            return json.load(f)
    url = f"{BASE}/cards/{card_id}" + ("?eza=true" if pre_eza else "")
    text = _get(session, url)
    data = _embedded_json(text, "datajson")
    with gzip.open(cache_file, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    time.sleep(delay)
    return data


def clean_lines(text):
    """Skill description -> the plain lines the game renders on screen."""
    if not text:
        return []
    text = re.sub(r"\{passiveImg:[^}]+\}", "", text)
    lines = []
    for line in text.splitlines():
        line = line.strip("*・ 　")
        if line and JP_RE.search(line):
            lines.append(line)
    return lines


def extract_record(data):
    """One index record from a card page's datajson."""
    card = data["card"]
    leader = data.get("leader_skill") or {}
    passive = data.get("passive_skill") or {}
    active = data.get("active_skill") or {}

    rec = {
        "name": card.get("name"),
        "title": leader.get("name"),  # leader skill name == card title
        "rarity": card.get("rarity"),
        "element": card.get("element"),
        "leader": leader.get("description"),
        "passive_name": passive.get("name"),
        "lines": clean_lines(passive.get("itemized_description")),
        "transformations": [t["id"] for t in data.get("transformations", [])
                            if t.get("id") != card.get("id")],
    }
    if isinstance(active, dict):
        active_lines = clean_lines(active.get("effect_description"))
        active_lines += clean_lines(active.get("condition_description"))
        if active_lines:
            rec["active_name"] = active.get("name")
            rec["active_lines"] = active_lines
    rec["has_eza"] = bool(data.get("eza_medals"))
    return rec


def load_index():
    path = HERE / "index.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {}


def save_index(index):
    path = HERE / "index.json"
    path.write_text(json.dumps(index, ensure_ascii=False, indent=1),
                    encoding="utf-8")
    print(f"index.json: {len(index)} cards")


def stratified_sample(cards, n, seed=7):
    """Spread across rarity/era, force in EZA and old/new extremes."""
    rng = random.Random(seed)
    pool = [c for c in cards if c["rarity"] >= 3]
    pool.sort(key=lambda c: c["open_at"] or 0)
    picks = {}

    def add(c):
        picks.setdefault(c["id"], c)

    add(next(c for c in pool if c["id"] == 1032261))  # spike's known card
    eza = [c for c in pool if c.get("eza")]
    for c in rng.sample(eza, min(3, len(eza))):
        add(c)
    for rarity in (3, 4, 5):
        rc = [c for c in pool if c["rarity"] == rarity]
        for c in rng.sample(rc, min(4, len(rc))):
            add(c)
    add(pool[0])   # oldest
    add(pool[-1])  # newest
    rest = [c for c in pool if c["id"] not in picks]
    while len(picks) < n and rest:
        add(rest.pop(rng.randrange(len(rest))))
    return list(picks.values())[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", nargs="*", type=int, help="specific card ids")
    ap.add_argument("--sample", type=int, help="stratified sample of N cards")
    ap.add_argument("--all", action="store_true", help="scrape everything")
    ap.add_argument("--min-rarity", type=int, default=3,
                    help="minimum rarity for --all/--sample (3=SSR)")
    ap.add_argument("--delay", type=float, default=1.0,
                    help="seconds between requests")
    ap.add_argument("--rebuild", action="store_true",
                    help="reparse cached pages only, no network")
    ap.add_argument("--follow-transformations", action="store_true",
                    default=True)
    args = ap.parse_args()

    session = _session()
    index = load_index()

    if args.rebuild:
        ids = [int(p.name.split(".")[0]) for p in CARD_CACHE.glob("*.json.gz")]
    elif args.ids:
        ids = args.ids
    else:
        cards = fetch_list(session)
        if args.sample:
            ids = [c["id"] for c in stratified_sample(cards, args.sample)]
        elif args.all:
            ids = [c["id"] for c in cards if c["rarity"] >= args.min_rarity]
        else:
            ap.error("need --ids, --sample, --all, or --rebuild")

    queue = list(ids)
    seen = set()
    failed = []
    while queue:
        card_id = queue.pop(0)
        if card_id in seen:
            continue
        seen.add(card_id)
        try:
            data = fetch_card(session, card_id, delay=args.delay)
            rec = extract_record(data)
            if rec["has_eza"]:
                pre = extract_record(
                    fetch_card(session, card_id, delay=args.delay,
                               pre_eza=True))
                if pre["lines"] != rec["lines"]:
                    rec["pre_eza_lines"] = pre["lines"]
                    rec["pre_eza_leader"] = pre["leader"]
        except Exception as e:
            print(f"  {card_id}: FAILED {e}", file=sys.stderr)
            failed.append(card_id)
            continue
        if not rec["title"] and not rec["lines"]:
            print(f"  {card_id} skipped (no kit — event/filler card)")
            continue
        index[str(card_id)] = rec
        eza_tag = " +preEZA" if rec.get("pre_eza_lines") else ""
        print(f"  {card_id} [{rec['rarity']}] {rec['title']} / {rec['name']}"
              f" ({len(rec['lines'])} lines{eza_tag})")
        if args.follow_transformations:
            for tid in rec["transformations"]:
                if tid not in seen:
                    queue.append(tid)
        if len(seen) % 50 == 0:
            save_index(index)

    save_index(index)
    if failed:
        print(f"failed ids: {failed}", file=sys.stderr)


if __name__ == "__main__":
    if sys.platform == "win32":
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    main()
