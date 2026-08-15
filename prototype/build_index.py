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

import kits as kitlib

BASE = "https://jpnja.dokkaninfo.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
HERE = Path(__file__).parent
CACHE = HERE / "cache"
CARD_CACHE = CACHE / "cards"
GLOBAL_CACHE = CACHE / "global"

# Bumped when the on-disk shape of index.json / kits.json.gz changes. The
# app refuses a downloaded pair older than it understands.
DATA_VERSION = 2

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
    keep = ["id", "name", "rarity", "eza", "open_at", "eza_open_at", "seza_open_at", "element", "lv_max"]
    cards = [{k: c.get(k) for k in keep} for c in cards]
    cache_file.write_text(json.dumps(cards, ensure_ascii=False),
                          encoding="utf-8")
    print(f"  {len(cards)} cards cached")
    return cards


def english_names(session, refresh=False):
    """id -> English name, from the GLOBAL dokkaninfo list page.

    Covers every card released on Global (nearly everything now that JP/GLB
    release simultaneously); JP-only stragglers just keep their JP name.
    """
    cache_file = CACHE / "list_en.json"
    if cache_file.exists() and not refresh:
        cards = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        print("fetching GLOBAL card list for English names (~12MB)...")
        text = _get(session, "https://dokkaninfo.com/cards?sort=open_at")
        cards = _embedded_json(text, "cardsjson")
        cards = [{"id": c["id"], "name": c.get("name")} for c in cards]
        cache_file.write_text(json.dumps(cards, ensure_ascii=False),
                              encoding="utf-8")
        print(f"  {len(cards)} global cards cached")
    return {c["id"]: c["name"] for c in cards if c.get("name")}


def fetch_card(session, card_id, delay=1.0, refresh=False, pre_eza=False,
               eza_step=None):
    """Raw datajson dict for one card, cached gzipped on disk.

    pre_eza=True fetches the ?eza=true view (NOT literally "pre-EZA": it's
    the other of DokkanInfo's two per-card views — see NOTES.md). Pass
    eza_step=<max_eza_step from the bare page> too: plain ?eza=true serves
    the wrong (untransformed) kit for some transformed EZA'd LR forms, and
    &step=<max> is a verified no-op where ?eza=true already worked.
    """
    CARD_CACHE.mkdir(parents=True, exist_ok=True)
    suffix = ".pre_eza.json.gz" if pre_eza else ".json.gz"
    cache_file = CARD_CACHE / f"{card_id}{suffix}"
    if cache_file.exists() and not refresh:
        with gzip.open(cache_file, "rt", encoding="utf-8") as f:
            return json.load(f)
    url = f"{BASE}/cards/{card_id}"
    if pre_eza:
        url += "?eza=true"
        if eza_step:
            url += f"&step={eza_step}"
    text = _get(session, url)
    data = _embedded_json(text, "datajson")
    with gzip.open(cache_file, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    time.sleep(delay)
    return data


def fetch_form_alt(session, card_id, eza_step, delay=1.0, refresh=False):
    """A transformed form's OWN EZA kit, from the transformation API.

    The form's card page (even with ?eza=true&step=N) serves the BASE card's
    EZA passive — verified on 4019411, where the page gives the base's
    passive #3933 and this endpoint gives the form's own #3934. This is the
    endpoint DokkanInfo's own transformation arrows call.
    """
    CARD_CACHE.mkdir(parents=True, exist_ok=True)
    cache_file = CARD_CACHE / f"{card_id}.tf_eza.json.gz"
    if cache_file.exists() and not refresh:
        with gzip.open(cache_file, "rt", encoding="utf-8") as f:
            return json.load(f)
    url = (f"{BASE}/api/cards/{card_id}/transformation"
           f"?eza=true&step={eza_step}")
    data = json.loads(_get(session, url))
    with gzip.open(cache_file, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    time.sleep(delay)
    return data


def fetch_global(session, card_id, alt_view, eza_step, delay=1.0, refresh=False):
    """The GLOBAL card page, whose kit is what the app displays.

    Mirrors what the app used to do at runtime: the alt (?eza=true) view
    when the card has one, with &step= because plain ?eza=true serves the
    wrong kit for some transformed EZA'd forms.

    Returns None when the card has no global page — a JP-only card, which
    the app can then say so about without a lookup.
    """
    GLOBAL_CACHE.mkdir(parents=True, exist_ok=True)
    suffix = f".alt{eza_step}" if alt_view else ""
    cache_file = GLOBAL_CACHE / f"{card_id}{suffix}.json.gz"
    if cache_file.exists() and not refresh:
        with gzip.open(cache_file, "rt", encoding="utf-8") as f:
            return json.load(f)
    url = f"{kitlib.GLOBAL}/cards/{card_id}"
    if alt_view:
        url += "?eza=true" + (f"&step={eza_step}" if eza_step else "")
    try:
        data = _embedded_json(_get(session, url), "datajson")
    except requests.exceptions.HTTPError as e:
        if getattr(e.response, "status_code", None) == 404:
            return None          # not on the global server yet
        raise
    with gzip.open(cache_file, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    time.sleep(delay)
    return data


def fetch_global_transformation(session, card_id, eza_step, delay=1.0, refresh=False):
    """A transformed form's own EZA kit, from the transformation API."""
    GLOBAL_CACHE.mkdir(parents=True, exist_ok=True)
    cache_file = GLOBAL_CACHE / f"{card_id}.tf{eza_step}.json.gz"
    if cache_file.exists() and not refresh:
        with gzip.open(cache_file, "rt", encoding="utf-8") as f:
            return json.load(f)
    url = (f"{kitlib.GLOBAL}/api/cards/{card_id}/transformation"
           f"?eza=true&step={eza_step}")
    data = json.loads(_get(session, url))
    with gzip.open(cache_file, "wt", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    time.sleep(delay)
    return data


def build_kit(session, card_id, rec, delay=1.0, refresh=False):
    """Extract one card's English kit, or None if it is JP-only."""
    alt_view = bool(rec.get("pre_eza_lines"))
    step = rec.get("eza_step") or 0
    data = fetch_global(session, card_id, alt_view, step, delay, refresh)
    if data is None:
        return None
    kit = kitlib.extract_kit(data, card_id)
    if alt_view and step and card_id >= 4_000_000:
        try:
            api = fetch_global_transformation(session, card_id, step, delay, refresh)
            kit = kitlib.overlay_form_kit(kit, api)
        except Exception as e:
            print(f"  {card_id}: transformation overlay skipped ({e})",
                  file=sys.stderr)
    return kit


def real_cards(index):
    """Card entries only — the index also carries a "__meta__" entry."""
    return {k: v for k, v in index.items() if not k.startswith("__")}


def load_packed_kits(index, kits_path):
    """Read previously packed kits back out, so a run that only fetched a
    handful of cards can still republish the whole file. CI has no scrape
    cache, so this is what keeps incremental syncs possible."""
    path = Path(kits_path)
    if not path.exists():
        return {}
    with gzip.open(path, "rb") as f:
        blob = f.read()
    out = {}
    for card_id, rec in real_cards(index).items():
        span = rec.get("kit")
        if not span:
            continue
        start, length = span
        if start + length > len(blob):
            continue
        try:
            out[card_id] = json.loads(blob[start:start + length].decode("utf-8"))
        except Exception:
            continue
    return out


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

    # SA names render in plain font on the card page — a strong signal there
    sa_names = []
    for sa in data.get("super_attacks") or []:
        name = ((sa.get("attack") or {}).get("name") or "").strip()
        if name and name not in sa_names:
            sa_names.append(name)
    if sa_names:
        rec["sa_names"] = sa_names
    rec["has_eza"] = bool(data.get("eza_medals"))
    # NB: eza_at is NOT set here — the card page carries no EZA dates at all.
    # main() stamps it from the card list, which is the only source.
    if data.get("max_eza_step"):
        rec["eza_step"] = data["max_eza_step"]
    return rec


def index_path(override=None):
    return Path(override) if override else HERE / "index.json"


def load_index(override=None):
    path = index_path(override)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {}


def save_index(index, override=None):
    path = index_path(override)
    path.write_text(json.dumps(index, ensure_ascii=False, indent=1),
                    encoding="utf-8")
    print(f"{path.name}: {sum(1 for k in index if not k.startswith('__'))} cards")


# Cards that exist in the card list but carry no kit at all (event/filler
# units) are skipped every time they are fetched — without remembering them
# a nightly sync would re-request the same ~200 dead ids forever. Committed
# so CI, which has no scrape cache, benefits from it too.
SKIP_LEDGER = HERE / "skipped_ids.json"


def load_skips():
    if SKIP_LEDGER.exists():
        return set(json.loads(SKIP_LEDGER.read_text(encoding="utf-8")))
    return set()


def save_skips(ids):
    SKIP_LEDGER.write_text(json.dumps(sorted(ids)), encoding="utf-8")


# Index cards shortly before release so the app works on day one. Far-future
# open_at values (2029/2030) are placeholders for story bosses that may never
# ship, and must stay excluded.
FUTURE_GRACE = 30 * 24 * 60 * 60


def eza_timestamp(row):
    """Newest EZA/SEZA opening on a card-list row, or 0.

    The list uses False — not None — for a card with no Super EZA, so
    `or 0` is doing real work here.
    """
    return max(int(row.get("eza_open_at") or 0),
               int(row.get("seza_open_at") or 0))


def ids_to_fetch(cards, index, skips, min_rarity, limit):
    """Cards needing a fetch, oldest first.

    Two reasons a card qualifies:
      - the index has never seen it, or
      - it is indexed but its EZA opened (or was upgraded to a Super EZA)
        after we last scraped it, so the kit we stored is now stale.

    The second case is the one an id-only diff cannot see: an EZA does not
    mint a new card id, it adds a second kit to an id we already have.
    """
    now = time.time()
    wanted = []
    for c in cards:
        if (c.get("rarity") or 0) < min_rarity:
            continue
        open_at = c.get("open_at") or 0
        if not (0 < open_at <= now + FUTURE_GRACE):
            continue
        rec = index.get(str(c["id"]))
        if rec is None:
            if c["id"] in skips:
                continue          # known to carry no kit at all
            wanted.append(c)
        elif eza_timestamp(c) > (rec.get("eza_at") or 0):
            # re-fetch even if ledgered: the ledger is about kit-less cards,
            # and this one demonstrably has one
            wanted.append(c)
    wanted.sort(key=lambda c: c.get("open_at") or 0)
    return [c["id"] for c in wanted[:limit]]


def seed_eza_timestamps(index, by_id):
    """Backfill eza_at on records written before the field existed.

    Without this every EZA'd card looks stale on the first run after the
    upgrade (~780 needless re-fetches). A record that already shows evidence
    of having been scraped WITH an EZA — medals, a step, or a second kit —
    gets the list's current timestamp, which is what a scrape today would
    have recorded. Records with no such evidence keep 0, so the cards whose
    EZA opened after we scraped them are correctly flagged stale.
    """
    seeded = 0
    for cid, rec in real_cards(index).items():
        if "eza_at" in rec:
            continue
        row = by_id.get(int(cid))
        had_eza = rec.get("has_eza") or rec.get("eza_step") or rec.get("pre_eza_lines")
        rec["eza_at"] = eza_timestamp(row) if (row and had_eza) else 0
        seeded += 1
    return seeded


def pack_and_save(index, kits, kits_path, index_override):
    """Write kits.json.gz and index.json together.

    Offsets in the index point into the kit blob, so the two files are only
    valid as a pair — always write them in this order, never separately.
    """
    packed, raw = kitlib.pack(real_cards(index), kits, kits_path, DATA_VERSION)
    # One meta entry rather than a field on all 5k records. Consumers must
    # skip "__"-prefixed keys; the offsets are only valid against the
    # kits.json.gz written in the same call.
    index["__meta__"] = {"data_version": DATA_VERSION, "kits": packed}
    save_index(index, index_override)
    size = Path(kits_path).stat().st_size
    print(f"{Path(kits_path).name}: {packed} kits, "
          f"{raw/1024/1024:.1f}MB raw -> {size/1024/1024:.2f}MB gzipped")


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
    ap.add_argument("--refresh-alt", action="store_true",
                    help="re-fetch ?eza=true pages (with &step=max) even if "
                         "cached — use once to upgrade a pre-step cache")
    ap.add_argument("--follow-transformations", action="store_true",
                    default=True)
    ap.add_argument("--sync", action="store_true",
                    help="fetch only cards the index is missing (for CI)")
    ap.add_argument("--index", help="path to the index to read/write; "
                                    "defaults to prototype/index.json")
    ap.add_argument("--max-new", type=int, default=400,
                    help="safety cap on how many cards one --sync may fetch")
    ap.add_argument("--kits", help="path to write the packed English kits; "
                                   "defaults to alongside --index")
    ap.add_argument("--backfill-kits", action="store_true",
                    help="fetch the English kit for every indexed card that "
                         "lacks one — the one-time global pass, run locally")
    args = ap.parse_args()

    session = _session()
    index = load_index(args.index)
    started_with = len(real_cards(index))
    skips = load_skips()
    kits_path = Path(args.kits) if args.kits else         index_path(args.index).with_name("kits.json.gz")
    kits = load_packed_kits(index, kits_path)
    print(f"kits already packed: {len(kits)}")

    # Card-list rows by id. Empty for modes that never fetch the list
    # (--rebuild, --ids); everything below must cope with that.
    by_id = {}
    stale = set()

    if args.rebuild:
        ids = [int(p.name.split(".")[0]) for p in CARD_CACHE.glob("*.json.gz")]
    elif args.ids:
        ids = args.ids
    elif args.sync:
        if not index:
            print("refusing to --sync against an empty index; "
                  "run --all once first", file=sys.stderr)
            return 1
        cards = fetch_list(session, refresh=True)
        by_id = {c["id"]: c for c in cards}
        seeded = seed_eza_timestamps(index, by_id)
        if seeded:
            print(f"backfilled eza_at on {seeded} existing records")
        ids = ids_to_fetch(cards, index, skips, args.min_rarity, args.max_new)
        stale = {i for i in ids if str(i) in index}
        print(f"index has {started_with} cards; {len(ids)} to fetch "
              f"({len(ids) - len(stale)} new, {len(stale)} with a newer EZA; "
              f"skip ledger: {len(skips)})")
        if not ids:
            print("NOTHING_NEW")
            return 0
    else:
        cards = fetch_list(session)
        by_id = {c["id"]: c for c in cards}
        if args.sample:
            ids = [c["id"] for c in stratified_sample(cards, args.sample)]
        elif args.all:
            ids = [c["id"] for c in cards if c["rarity"] >= args.min_rarity]
        elif args.backfill_kits:
            # standalone: fetch nothing new, just fill in missing kits.
            # Keeps kit progress checkpointed by the backfill pass rather
            # than riding along with a full --rebuild.
            ids = []
            cards = []
        else:
            ap.error("need --ids, --sample, --all, --sync, --rebuild, "
                     "or --backfill-kits")

    queue = list(ids)
    seen = set()
    failed = []
    while queue:
        card_id = queue.pop(0)
        if card_id in seen:
            continue
        seen.add(card_id)
        # A card whose EZA just opened has a cached page from before it
        # existed; reusing that would re-parse the old kit and conclude
        # nothing changed.
        force = card_id in stale or args.refresh_alt
        try:
            data = fetch_card(session, card_id, delay=args.delay, refresh=force)
            rec = extract_record(data)
            if rec["has_eza"]:
                step = data.get("max_eza_step")
                if card_id >= 4_000_000 and step:
                    # transformed form: its own EZA kit lives behind the
                    # transformation API, not its card page
                    alt = fetch_form_alt(session, card_id, step,
                                         delay=args.delay,
                                         refresh=force)
                else:
                    alt = fetch_card(session, card_id, delay=args.delay,
                                     pre_eza=True, refresh=force,
                                     eza_step=step)
                pre = extract_record(alt)
                if pre["lines"] != rec["lines"]:
                    rec["pre_eza_lines"] = pre["lines"]
                    if pre["leader"]:
                        rec["pre_eza_leader"] = pre["leader"]
                for name in pre.get("sa_names") or []:
                    # EZA'd SA names carry a (極限) suffix on screen
                    if name not in rec.get("sa_names", []):
                        rec.setdefault("sa_names", []).append(name)
        except Exception as e:
            print(f"  {card_id}: FAILED {e}", file=sys.stderr)
            failed.append(card_id)
            # 404/500 from this site are deterministic (a handful of ids are
            # simply broken upstream), so stop retrying them every sync.
            # Transient codes are retried inside _get and never land here.
            status = getattr(getattr(e, "response", None), "status_code", None)
            if status in (404, 500):
                skips.add(card_id)
            continue
        if not rec["title"] and not rec["lines"]:
            # Only ledger RELEASED cards. An unreleased one legitimately has
            # no kit yet, and ledgering it would mean never retrying it —
            # permanently missing from the index after it launches.
            row = by_id.get(card_id)
            released = (row or {}).get("open_at") or 0
            if released and released <= time.time():
                print(f"  {card_id} skipped (no kit — event/filler card)")
                skips.add(card_id)
            else:
                print(f"  {card_id} skipped (no kit yet — not released)")
            continue

        # Stamp the EZA date from the list. When the list is unavailable
        # (--rebuild, --ids) keep whatever was already stored: this record
        # replaces the old one wholesale, so omitting it would silently
        # erase the timestamp and make every EZA'd card look stale again.
        previous = index.get(str(card_id), {})
        row = by_id.get(card_id)
        rec["eza_at"] = eza_timestamp(row) if row else (previous.get("eza_at") or 0)
        index[str(card_id)] = rec

        # The English kit ships with the index now, so it is collected here
        # rather than fetched by every user at runtime.
        try:
            kit = build_kit(session, card_id, rec, delay=args.delay,
                            refresh=force)
            if kit is None:
                rec["global"] = False    # JP-only: app says so, no lookup
                kits.pop(str(card_id), None)
            else:
                rec.pop("global", None)
                kits[str(card_id)] = kit
        except Exception as e:
            print(f"  {card_id}: kit FAILED {e}", file=sys.stderr)
        eza_tag = " +preEZA" if rec.get("pre_eza_lines") else ""
        print(f"  {card_id} [{rec['rarity']}] {rec['title']} / {rec['name']}"
              f" ({len(rec['lines'])} lines{eza_tag})")
        if args.follow_transformations:
            for tid in rec["transformations"]:
                if tid not in seen:
                    queue.append(tid)
        if len(seen) % 50 == 0:
            save_index(index, args.index)

    try:
        en = english_names(session)
        tagged = 0
        for cid, rec in real_cards(index).items():
            name = en.get(int(cid))
            if name:
                rec["name_en"] = name
                tagged += 1
        print(f"English names attached: {tagged}/{len(real_cards(index))}")
    except Exception as e:
        print(f"English-name merge skipped: {e}", file=sys.stderr)

    # One-time (and repair) pass: every indexed card that still has no
    # English kit. Run locally — it is ~5k requests, not something to point
    # a scheduled job at.
    if args.backfill_kits:
        todo = [c for c in real_cards(index)
                if c not in kits and index[c].get("global") is not False]
        print(f"backfilling kits for {len(todo)} cards...")
        for n, cid in enumerate(todo, 1):
            try:
                kit = build_kit(session, int(cid), index[cid], delay=args.delay)
            except Exception as e:
                print(f"  {cid}: kit FAILED {e}", file=sys.stderr)
                continue
            if kit is None:
                index[cid]["global"] = False
            else:
                kits[cid] = kit
            if n % 100 == 0:
                print(f"  {n}/{len(todo)}  ({len(kits)} kits)")
                pack_and_save(index, kits, kits_path, args.index)

    # An automated run must never be able to shrink the index — a partial
    # scrape or a site-wide outage would otherwise ship a gutted index to
    # the app on the next release.
    if len(real_cards(index)) < started_with:
        print(f"REFUSING to write: index shrank {started_with} -> "
              f"{len(real_cards(index))}",
              file=sys.stderr)
        return 1

    pack_and_save(index, kits, kits_path, args.index)
    save_skips(skips)
    if failed:
        print(f"failed ids: {failed}", file=sys.stderr)
    added = len(real_cards(index)) - started_with
    print(f"ADDED {added}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main() or 0)
