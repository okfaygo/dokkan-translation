# Dokkan Translate — Android v0.1

Share-sheet MVP: screenshot a card's passive-detail screen in JP Dokkan,
tap **Share → Dokkan Translate**, get the English kit. No special
permissions (just INTERNET). There's also a "pick a screenshot" button for
testing from the gallery.

## Pipeline

1. `ocr/OcrEngine` — ML Kit Text Recognition v2 (Japanese), bundled model,
   fully on-device. Keeps recognized lines containing Japanese characters.
2. `match/CardIndex` — loads `assets/index.json` (built by
   `../prototype/build_index.py`; 5,169 cards, ~3MB).
3. `match/Matcher` — port of `../prototype/match.py`: per OCR line, best
   normalized-indel ratio (identical to rapidfuzz `fuzz.ratio`, verified to
   float precision) against each card's passive/pre-EZA/active lines +
   title + name; scores >= 70 summed per card; highest total wins.
4. `api/DokkanWiki` — fetches `https://dokkan.wiki/api/cards/<id>`, caches
   on disk permanently (kits are immutable in practice), renders leader /
   passive / super attacks / links / categories.
5. `ui/AppScreen` — Compose UI. Shows the kit plus a "not the right card?"
   list of the next 3 candidates.

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
