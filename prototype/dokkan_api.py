"""Fetch and render an English card kit from the dokkan.wiki API.

Usage:
    python dokkan_api.py 1021501            # fetch by card id
    python dokkan_api.py --file card.json   # render pre-fetched JSON
"""

import argparse
import json
import re
import textwrap

API = "https://dokkan.wiki/api/cards/{}"

ELEMENTS = {0: "AGL", 1: "TEQ", 2: "INT", 3: "STR", 4: "PHY"}
RARITIES = {3: "SSR", 4: "UR", 5: "LR"}


def element_name(code):
    # 10/11.. = Super, 20/21.. = Extreme; low digit is the type
    prefix = {1: "Super ", 2: "Extreme "}.get(code // 10, "")
    return prefix + ELEMENTS.get(code % 10, f"?{code}")


def clean(text):
    """Strip dokkan.wiki markup like {passiveImg:up_g} and *headers*."""
    text = re.sub(r"\{passiveImg:[^}]+\}", "", text)
    return text.strip()


def fetch(card_id):
    import requests  # local import so --file mode works without requests

    r = requests.get(API.format(card_id), timeout=15)
    r.raise_for_status()
    return r.json()


def render(payload):
    card = payload["card"]
    lines = []
    lines.append(f"[{RARITIES.get(card['rarity'], card['rarity'])} "
                 f"{element_name(card['element'])}] "
                 f"{card['title']} — {card['name']}")
    lines.append("")
    lines.append(f"LEADER SKILL: {clean(card.get('leader_skill', '-'))}")
    lines.append("")
    lines.append(f"PASSIVE ({card.get('passive_skill_name', '')}):")
    for row in clean(card.get("passive_skill_itemized_desc", "")).splitlines():
        row = row.strip()
        if row.startswith("*") and row.endswith("*"):
            lines.append(f"  [{row.strip('*')}]")
        elif row:
            lines.append(f"    {clean(row)}")
    lines.append("")
    for sp in payload.get("specials", []):
        lines.append(f"SUPER ATTACK: {sp['name']} — "
                     + clean(sp["description"]).replace("\n", " "))
    links = [l["name"] for l in payload.get("card_links", [])]
    cats = [c["name"] for c in payload.get("categories", [])]
    lines.append("")
    lines.append("LINKS: " + ", ".join(links))
    lines.append(textwrap.fill("CATEGORIES: " + ", ".join(cats), width=100))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("card_id", nargs="?")
    ap.add_argument("--file", help="render a saved API JSON payload")
    args = ap.parse_args()

    if args.file:
        with open(args.file, encoding="utf-8") as f:
            payload = json.load(f)
    elif args.card_id:
        payload = fetch(args.card_id)
    else:
        ap.error("need a card id or --file")
    print(render(payload))


if __name__ == "__main__":
    main()
