"""Build the JP mission text -> official English text table.

Same identify-and-lookup idea as the card index, applied to missions: the
app OCRs the JP mission screen, fuzzy-matches the lines against the JP
text here, and shows the English text Global ships for the SAME mission id.
No machine translation anywhere — mission ids are shared between servers
(verified: all 125 missions on page 1 of the event list exist on both, and
the Global text is the official localisation).

Source: DokkanInfo's mission pages embed the data as entity-escaped JSON
(`missioncategoriesset="..."`), same trick as the card pages:
  type=1 daily, 2 normal, 4 Supreme Kai trials  -> one list, no pagination
  type=3 event ("extra") missions, type=5 panel -> Laravel pagination
                                                    ({data, last_page, ...})

Usage:
    python build_missions.py [--out PATH] [--delay 1.5]   # full, ~100 pages
    python build_missions.py --sync        # only what the table is missing
    python build_missions.py --rebuild     # reparse cache/, no network

--sync is what CI runs: the paginated lists are newest-first, so it stops
paging as soon as a page brings nothing new — typically ~12 requests a
week instead of ~100. A JP mission still waiting for its English counts as
"missing" on the Global side, so late translations get picked up too.

Output: {"__meta__": {...}, "<mission id>": {
            "c": category id, "n": JP name, "d": JP description,
            "en": EN name or null, "ed": EN description or null},
         "__cats__": {"<category id>": JP event name}}
Category (event) names are match keys too: the mission screen shows the
event's name, and it is what separates the 402 missions all literally
named "clear the specified Extreme Z-Battle under special conditions".
EN is null for missions the Global server doesn't have (yet) — the app
says so rather than guessing a translation.
"""

import argparse
import json
import re
import sys
import time
from pathlib import Path

import requests

from build_index import _embedded_json, _get, _session

JP = "https://jpnja.dokkaninfo.com"
GLOBAL = "https://dokkaninfo.com"
TYPES = (1, 2, 3, 4, 5)
HERE = Path(__file__).parent
DEFAULT_OUT = HERE.parent / "android/app/src/main/assets/missions.json"
CACHE = HERE / "cache"

# Bumped when the shape of missions.json changes; the app refuses newer.
DATA_VERSION = 1

# "{cards:card_id:1017730,1017731}" — the site renders card thumbnails
# there; in plain text it is noise (and would pollute the match keys).
MARKUP_RE = re.compile(r"\{[a-zA-Z_]+:[^{}]*\}")


def fetch_type(session, base, mission_type, delay, known=None):
    """Missions of one type on one server: {id: (category, mission)}.

    With `known`, stops after the first page whose missions are all known
    (lists are newest-first, so everything further back is old).
    """
    out = {}
    page, last = 1, 1
    while page <= last:
        url = f"{base}/missions?type={mission_type}&page={page}"
        data = _embedded_json(_get(session, url), "missioncategoriesset")
        if isinstance(data, dict):          # paginated types
            last = data.get("last_page") or 1
            categories = data.get("data") or []
        else:
            categories = data
        fresh = False
        for cat in categories:
            missions = cat.get("missions") or []
            if isinstance(missions, dict):
                missions = missions.values()
            for m in missions:
                out[m["id"]] = (cat, m)
                fresh = fresh or known is None or m["id"] not in known
        print(f"  {base} type={mission_type} page {page}/{last}: "
              f"{len(out)} missions", file=sys.stderr)
        page += 1
        time.sleep(delay)
        if not fresh:
            break
    return out


def fetch_all(session, base, delay, cache_name, rebuild, known=None):
    """{id: (category, mission)}, raw pages cached for --rebuild."""
    cache_file = CACHE / cache_name
    if rebuild:
        raw = json.loads(cache_file.read_text(encoding="utf-8"))
    else:
        raw = {}
        for t in TYPES:
            raw.update(fetch_type(session, base, t, delay, known))
        if known is None:       # a sync only saw the newest pages
            CACHE.mkdir(exist_ok=True)
            cache_file.write_text(json.dumps(raw, ensure_ascii=False),
                                  encoding="utf-8")
    # json round-trips the int keys as strings
    return {int(k): tuple(v) for k, v in raw.items()}


def clean_ja(text):
    """Keep the game's own line breaks — they are where the OCR lines break."""
    text = MARKUP_RE.sub("", text or "")
    lines = [ln.strip(" 　") for ln in text.split("\n")]
    return "\n".join(ln for ln in lines if ln)


def clean_en(text):
    """One flowing string: the \\n are the game's wrap points, not paragraphs,
    except a blank line, which is a real paragraph break."""
    text = MARKUP_RE.sub("", text or "")
    paragraphs = re.split(r"\n\s*\n", text)
    flowed = [re.sub(r"\s+", " ", p).strip() for p in paragraphs]
    return "\n".join(p for p in flowed if p) or None


# "1225_【特別編】..." — an internal id prefix the game never displays
CAT_PREFIX_RE = re.compile(r"^\d+_")


def build(jp, gl):
    table = {}
    cats = {}
    for mid, (cat, m) in sorted(jp.items()):
        name = clean_ja(m.get("name"))
        if not name:
            continue
        en = gl.get(mid)
        cat_name = CAT_PREFIX_RE.sub("", clean_ja(cat.get("name")))
        if cat_name:
            cats[str(cat["id"])] = cat_name.replace("\n", "")
        table[str(mid)] = {
            "c": cat["id"],
            "n": name,
            "d": clean_ja(m.get("description")),
            "en": clean_en(en[1].get("name")) if en else None,
            "ed": clean_en(en[1].get("description")) if en else None,
        }
    table["__cats__"] = cats
    return table


def merge(old, jp, gl):
    """--sync: the old table, plus what the newest pages added or changed."""
    table = {k: dict(v) for k, v in old.items() if not k.startswith("__")}
    fresh = build(jp, gl)
    cats = dict(old.get("__cats__", {}))
    cats.update(fresh.pop("__cats__"))
    for mid, row in fresh.items():
        prev = table.get(mid)
        # the sync stops paging per server, so a JP mission re-read here may
        # not have been re-read on Global: absence is not "no English"
        if prev and row["en"] is None:
            row["en"], row["ed"] = prev.get("en"), prev.get("ed")
        table[mid] = row
    # English that arrived for a JP mission fetched in an earlier run
    for mid, (_, m) in gl.items():
        row = table.get(str(mid))
        if row is not None and mid not in jp:
            row["en"] = clean_en(m.get("name"))
            row["ed"] = clean_en(m.get("description"))
    table["__cats__"] = cats
    return table


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--delay", type=float, default=1.0)
    ap.add_argument("--rebuild", action="store_true",
                    help="reparse cache/missions_*.json, no network")
    ap.add_argument("--sync", action="store_true",
                    help="fetch only the newest pages, merge into --out")
    args = ap.parse_args()
    out = Path(args.out)

    session = _session()
    if args.sync:
        old = json.loads(out.read_text(encoding="utf-8"))
        rows = {k: r for k, r in old.items() if not k.startswith("__")}
        known_jp = {int(k) for k in rows}
        known_gl = {int(k) for k, r in rows.items() if r.get("en")}
        jp = fetch_all(session, JP, args.delay, "missions_jp.json", False, known_jp)
        gl = fetch_all(session, GLOBAL, args.delay, "missions_gl.json", False, known_gl)
        table = merge(old, jp, gl)
    else:
        jp = fetch_all(session, JP, args.delay, "missions_jp.json", args.rebuild)
        gl = fetch_all(session, GLOBAL, args.delay, "missions_gl.json", args.rebuild)
        table = build(jp, gl)
    missions = {k: r for k, r in table.items() if not k.startswith("__")}
    translated = sum(1 for r in missions.values() if r["en"])

    # A truncated scrape must not replace a good table.
    if out.exists():
        old = json.loads(out.read_text(encoding="utf-8"))
        old_count = sum(1 for k in old if not k.startswith("__"))
        if len(missions) < old_count * 0.9:
            sys.exit(f"mission table shrank ({old_count} -> {len(missions)}), "
                     "refusing to write")
        old.pop("__meta__", None)
        if old == table:
            print("NOTHING_NEW")
            return

    table["__meta__"] = {"data_version": DATA_VERSION,
                         "missions": len(missions), "translated": translated}
    out.write_text(json.dumps(table, ensure_ascii=False, separators=(",", ":")),
                   encoding="utf-8")
    print(f"{len(missions)} missions ({translated} with official English) "
          f"-> {out}")


if __name__ == "__main__":
    main()
