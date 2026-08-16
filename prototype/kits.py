"""English kit extraction — the parsing the Android app used to do at runtime.

The app fetched a ~211KB card page per card, per user, from DokkanInfo to
render ~1.9KB of kit. That is fine for one person and rude at community
scale, so extraction moved here: CI does it once and ships the result.

This is a port of api/DokkanInfo.kt. Keep the two in step until that file
is deleted — `python kits.py <card_id>` prints a kit for eyeballing against
what the app renders.
"""

import gzip
import json
import re
import time

GLOBAL = "https://dokkaninfo.com"
PASSIVE_IMG = re.compile(r"\{passiveImg:[^}]+\}")

RARITIES = {2: "SR", 3: "SSR", 4: "UR", 5: "LR"}
ELEMENTS = {0: "AGL", 1: "TEQ", 2: "INT", 3: "STR", 4: "PHY"}


def element_name(code):
    try:
        code = int(code)
    except (TypeError, ValueError):
        return "?"
    prefix = {1: "Super ", 2: "Extreme "}.get(code // 10, "")
    return prefix + ELEMENTS.get(code % 10, "?")


def _clean(text):
    return PASSIVE_IMG.sub("", text or "").strip()


def _text(obj, key, fallback=None):
    """dict.get that treats JSON null and empty string as absent."""
    if not isinstance(obj, dict):
        return fallback
    value = obj.get(key)
    if value is None or value == "":
        return fallback
    return value


def parse_itemized(text):
    """Itemized passive -> [(is_header, text)] rows.

    Headers are wrapped in *...* and may span several lines; items start
    with "- " (EN) or "・" (JP); anything else continues the previous row,
    because the source wraps sentences mid-way. {passiveImg:..} tokens are
    KEPT — the app renders them as the in-game icons.
    """
    rows = []
    header_open = False
    for raw in (text or "").strip().split("\n"):
        line = raw.strip(" 　")
        if not line:
            continue
        if header_open:
            closes = line.endswith("*")
            rows[-1] = (rows[-1][0], rows[-1][1] + " " + line.strip("*").strip())
            if closes:
                header_open = False
        elif line.startswith("*"):
            closes = len(line) > 1 and line.endswith("*")
            rows.append((True, line.strip("*").strip()))
            header_open = not closes
        elif line[0] in "-・":
            rows.append((False, line.lstrip("-・ 　")))
        elif not rows:
            rows.append((False, line))
        else:
            rows[-1] = (rows[-1][0], rows[-1][1] + " " + line)
    return rows


def render_effect(special):
    """A super attack's numeric effect, which the prose omits.

    Codes follow DokkanInfo's own renderer: efficacy_type picks the stat,
    calc_option the direction (2 = raise, 3 = lower), eff_value1/2 carry
    the percentages. Unknown codes are skipped rather than guessed at —
    the site has no case for them either.
    """
    raise_ = special.get("calc_option") != 3
    v1 = special.get("eff_value1") or 0
    v2 = special.get("eff_value2") or 0
    turns = special.get("turn") or 0
    chance = special.get("prob")
    chance = 100 if chance is None else chance

    def stat(up, down, value):
        return "{passiveImg:%s}%s%%" % (up if raise_ else down, value)

    efficacy = special.get("efficacy_type")
    if efficacy == 1:
        body = stat("atk_up", "atk_down", v1)
    elif efficacy == 2:
        body = stat("def_up", "def_down", v1)
    elif efficacy == 3:
        body = stat("atk_up", "atk_down", v1) + " " + stat("def_up", "def_down", v2)
    elif efficacy == 9:
        body = "{passiveImg:stun}Stun"
    elif efficacy == 48:
        body = "{passiveImg:astute}Seal"
    else:
        return None

    if turns > 0:
        body += " for 1 turn" if turns == 1 else f" for {turns} turns"
    if 1 <= chance <= 99:
        body += f" ({chance}% chance)"
    return body


def parse_supers(data):
    supers = []
    seen = set()
    for entry in data.get("super_attacks") or []:
        attack = entry.get("attack") or {}
        name = _text(attack, "name")
        if not name:
            continue
        desc = re.sub(r" {2,}", " ", _clean(attack.get("description")).replace("\n", " "))
        ki = entry.get("eball_num_start") or 0
        key = (name, desc, ki)
        if key in seen:
            continue
        seen.add(key)
        effects = [e for e in (render_effect(s) for s in entry.get("specials") or []) if e]
        supers.append({
            "name": name,
            "desc": desc,
            "ki": ki,
            "style": _text(entry, "style", ""),
            "condition": (_text(attack, "causality_description") or "").replace("\n", " ") or None,
            "effects": effects,
        })
    return supers


def _names(data, field):
    return [n for n in ((_text(e, "name") for e in data.get(field) or [])) if n]


def extract_kit(data, card_id):
    """The card page's display fields — what Kit renders in the app."""
    card = data.get("card") or {}
    leader = data.get("leader_skill") or {}
    passive = data.get("passive_skill") or {}
    active = data.get("active_skill") or {}

    active_desc = " — ".join(
        _clean(t).replace("\n", " ")
        for t in (_text(active, "effect_description"), _text(active, "condition_description"))
        if t
    )

    transformations = []
    for form in data.get("transformations") or []:
        fid = str(form.get("id"))
        if fid == str(card_id):
            continue
        fname = (_text(form, "name") or "").replace("\n", " ")
        if fname:
            transformations.append([fid, fname])

    return {
        "title": _text(leader, "name", ""),
        "name": (_text(card, "name", "")).replace("\n", " "),
        "rarity": RARITIES.get(card.get("rarity"), str(card.get("rarity"))),
        "element": element_name(_text(card, "element", -1)),
        "leader": _clean(_text(leader, "description", "-")).replace("\n", " "),
        "passive_name": _text(passive, "name", ""),
        "passive": parse_itemized(_text(passive, "itemized_description", "")),
        "supers": parse_supers(data),
        "active_name": _text(active, "name", ""),
        "active": active_desc,
        "links": _names(data, "links"),
        "categories": _names(data, "categories"),
        "transformations": transformations,
    }


def overlay_form_kit(kit, api):
    """A transformed form's card page serves the BASE card's EZA passive.

    The form's own kit exists only behind the transformation API, so the
    form-specific parts are overlaid; leader, categories and the form list
    from the page are already correct.
    """
    passive = api.get("passive_skill") or {}
    itemized = _text(passive, "itemized_description")
    if itemized:
        kit["passive"] = parse_itemized(itemized)
    name = _text(passive, "name")
    if name:
        kit["passive_name"] = name
    supers = parse_supers(api)
    if supers:
        kit["supers"] = supers
    links = _names(api, "links")
    if links:
        kit["links"] = links
    return kit


# ---- packing ------------------------------------------------------------

def pack(index, kits, kits_path, version):
    """Write kits as concatenated JSON objects and record byte offsets.

    One kit is needed at a time, so the app seeks to an offset and parses
    ~2KB rather than loading ~9.5MB of JSON into a background service.
    Written gzipped: the APK carries ~1MB instead of ~9.5MB, and the app
    inflates it once on first run so the offsets are usable.
    """
    blob = bytearray()
    packed = 0
    for card_id, rec in index.items():
        kit = kits.get(card_id)
        if kit is None:
            rec.pop("kit", None)
            continue
        encoded = json.dumps(kit, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        rec["kit"] = [len(blob), len(encoded)]
        blob.extend(encoded)
        packed += 1

    with gzip.open(kits_path, "wb", compresslevel=9) as f:
        f.write(bytes(blob))
    return packed, len(blob)


if __name__ == "__main__":
    import sys
    import urllib.request
    import html as H

    cid = sys.argv[1] if len(sys.argv) > 1 else "1032261"
    req = urllib.request.Request(
        f"{GLOBAL}/cards/{cid}",
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"},
    )
    page = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "ignore")
    payload = json.loads(H.unescape(re.search(r'datajson="([^"]*)"', page).group(1)))
    print(json.dumps(extract_kit(payload, cid), ensure_ascii=False, indent=1))
