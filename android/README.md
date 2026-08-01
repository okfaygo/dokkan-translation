# Dokkan Translate — Android v0.1

Share-sheet MVP: screenshot a card's passive-detail screen in JP Dokkan,
tap **Share → Dokkan Translate**, get the English kit. No special
permissions (just INTERNET). There's also a "pick a screenshot" button for
testing from the gallery.

## Pipeline

1. `ocr/OcrEngine` — ML Kit Text Recognition v2 (Japanese), bundled model,
   fully on-device. Keeps recognized lines containing Japanese characters.
2. `match/CardIndex` — loads `assets/index.json` (built by
   `../prototype/build_index.py`; 5,169 cards incl. English names, ~3MB).
   Two key groups per card, mirroring DokkanInfo's two per-card views
   (bare URL vs `?eza=true`): passive lines + active/leader lines + title
   + name in the main group; the `?eza=true` view's lines in the alt group.
3. `match/Matcher` — port of `../prototype/match.py`: per OCR line, best
   normalized-indel ratio (identical to rapidfuzz `fuzz.ratio`, verified to
   float precision) against each card's keys; scores >= 70, weighted by
   line length (full sentences outvote category chips / UI labels), summed
   per card. The two views are scored separately, and the winner remembers
   WHICH view matched. Candidates within 2% of the top score are re-ranked
   base-cards-first, then rarity, then id — awakened beats unawakened, and
   a base card beats its own transformed form.
4. `api/DokkanInfo` — fetches the GLOBAL `dokkaninfo.com/cards/<id>` page
   (embedded `datajson`), permanent disk cache under `cache/dokkaninfo/`
   (the v0.1 `cache/kits/` dir is purged on first use — its dokkan.wiki
   payloads read as blank kits under this parser). Fetches whichever view
   MATCHED the screenshot: the bare/?eza=true views aren't consistently
   base/EZA (bare = base kit for EZA'd URs but SEZA kit for LRs), so the
   screenshot itself decides which kit the player is looking at. No
   pre/post-EZA labels anywhere, deliberately. Replaced dokkan.wiki,
   which went stale.
5. `ui/AppScreen` — Compose UI. Kit sections incl. Active Skill and a
   Transformations section (buttons jump between a card's forms), plus a
   "not the right card?" list of the next 3 candidates (English names).

## Build & run

Open this `android/` directory in Android Studio (Ladybug or newer), let it
sync, and Run on a connected phone (USB debugging on). No signing config
needed for a debug build.

CLI alternative if you have the Android SDK + Gradle installed:

```
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Updating the bundled index

After re-running the scraper:

```
python ../prototype/build_index.py --all        # refresh cache + index.json
copy ..\prototype\index.json app\src\main\assets\index.json
```

## Deferred to v0.2+

Floating bubble + MediaProjection capture (auto-identify without leaving the
game), screen-state auto-detection, index auto-update, EZA-state toggle in
the UI, lookup history.
