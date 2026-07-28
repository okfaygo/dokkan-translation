# dokkan-translation

Android app to show English kits for JP Dokkan Battle cards from in-game
screenshots. Identify-and-lookup, NOT machine translation: OCR the screenshot,
fuzzy-match against a JP-text -> card-id index, fetch the community-translated
English kit from `https://dokkan.wiki/api/cards/<id>` (structured JSON: leader,
itemized passive, SA, links, categories, transformations; English-only,
`?lang=ja` is ignored).

## State

`prototype/` contains a validated Python spike (see `prototype/NOTES.md` for
full findings):
- `ocr_pipeline.py` — preprocess variants + Tesseract `jpn` -> candidate lines
- `match.py` — rapidfuzz matching against an index (index.json not built yet)
- `dokkan_api.py` — fetch/render English kit
- `tessdata/` — `jpn` + `jpn_vert` traineddata (extracted from npm packages
  `@tesseract.js-data/jpn` / `jpn_vert`; not pip/apt-installable)

Validated on real screenshots (SSJ4 Goku (Mini) DAIMA = card 1032261):
identify -> fetch -> render works end to end.

## Key design decisions (learned, don't relitigate without new data)

- Match on **passive-detail screen text** (plain UI font, near-perfect OCR),
  not the card's vertical stylized name/title (Tesseract fails on it even
  with masking/deskew/jpn_vert; stylized SA names also fail).
- Multi-line fuzzy voting: rapidfuzz ratio per OCR line vs index keys,
  threshold ~70, sum scores per card id. Robust to digit-level OCR errors.
- Digits next to icon badges OCR poorly (気力+2 -> 気力T8); don't rely on
  numbers for matching.

## TODO

1. Build the JP-text -> card-id index: **scraper built & validated
   (2026-07-28)** — `prototype/build_index.py` scrapes DokkanInfo's
   server-embedded JSON (`jpnja.dokkaninfo.com`, JP-server/JP-language;
   earlier "client-rendered, unscrapable" finding was outdated). Validated
   on a 20-card stratified sample: titles, passive lines, EZA pre/post kits
   (`?eza=true` = pre-EZA), transformation following, filler filtering all
   work; `match.py` voting picks the right card under heavy synthetic OCR
   noise; ids spot-checked against dokkan.wiki EN API. Details in
   `prototype/NOTES.md`. **Remaining: run the full ~11k-card scrape
   (`python build_index.py --all`, ~3.5h at 1 req/s).**
2. Android app (Kotlin): floating bubble + MediaProjection capture, ML Kit
   Text Recognition v2 (Japanese) instead of Tesseract — retest whether ML Kit
   reads the vertical stylized titles (it likely handles rotation better),
   bundled SQLite index, live kit fetch + cache, bottom-sheet UI.
3. EZA/transformed states: same card id, different passive — API's
   `optimal_awakening_growths` / `transformations` cover this.

No ToS concerns: screen reading only, no game modification.
