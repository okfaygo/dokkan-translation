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

1. ~~Build the JP-text -> card-id index~~ **DONE (2026-07-29)** —
   `prototype/build_index.py` scraped DokkanInfo's server-embedded JSON
   (`jpnja.dokkaninfo.com`). Full run complete: **5,169 cards indexed**
   (767 with pre-EZA kits, 294 with actives; 23.5k passive lines; 3MB
   `index.json`, gitignored — rebuild from `prototype/cache/` with
   `--rebuild`). 5,899 kit-less event/filler units skipped; 13 junk ids
   fail server-side (DokkanInfo 500s/redirect loops) plus 2 real transformed
   forms (4020341 SSGSS Vegeta Evolution, 4021821 Videl) whose pages are
   broken on DokkanInfo itself — base forms are indexed, acceptable gap.
   Full-index stress test: noisy modern UR wins 741 vs 618 runner-up;
   worst-case 2-line old SSR still ranks #1 (tied only with its own
   awakening sibling, same kit). `rank()` over 5,169 cards: 0.3s.
2. Android app (Kotlin): floating bubble + MediaProjection capture, ML Kit
   Text Recognition v2 (Japanese) instead of Tesseract — retest whether ML Kit
   reads the vertical stylized titles (it likely handles rotation better),
   bundled SQLite index, live kit fetch + cache, bottom-sheet UI.
3. EZA/transformed states: same card id, different passive — API's
   `optimal_awakening_growths` / `transformations` cover this.

No ToS concerns: screen reading only, no game modification.
