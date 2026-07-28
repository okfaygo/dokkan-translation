# Prototype notes

Findings from the initial spike (2026-07-28).

## Goal
JP Dokkan screenshot -> identify card -> show English kit. No translation:
the community has already translated every JP kit; we identify and look up.

## Pipeline
1. `ocr_pipeline.py` — preprocess (upscale, invert, adaptive/Otsu threshold),
   Tesseract `jpn` over all variants (psm 6 + 11), merge into ranked
   candidate lines containing kana/kanji.
2. `match.py` — rapidfuzz the candidates against a JP-title -> card-id index.
   Card **titles** (the flavor line, e.g. 果てなき進化の力), not character
   names, are the near-unique key.
3. `dokkan_api.py` — `https://dokkan.wiki/api/cards/<id>` returns the full
   structured English kit (leader, itemized passive, SA, links, categories,
   transformations). Render it.

## Data findings
- dokkan.wiki API is English-only; `?lang=ja` is ignored. No public search
  endpoint found at `/api/search`.
- DokkanInfo is client-rendered (empty HTML shell for scrapers).
- JP titles per card are available on the fandom wiki card pages
  (dbz-dokkanbattle.fandom.com, MediaWiki API) -> best source for a one-time
  index scrape. The game's own decrypted SQLite DB also contains all JP
  strings keyed by card id (see e.g. github.com/bensnilloc/
  Dragonball-Z-Dokkan-Battle-Database-Decryptor) but that's a grayer area.
- Tesseract jpn traineddata isn't pip-installable; the npm package
  `@tesseract.js-data/jpn` bundles a standard `jpn.traineddata` (gzipped) —
  extracted copy lives in `prototype/tessdata/`.

## Production plan (Android)
- Kotlin app, floating bubble + MediaProjection screen capture.
- ML Kit Text Recognition v2 (Japanese) on-device instead of Tesseract.
- Bundled SQLite index (JP title -> card id), refreshed from a small
  scraper job; kit JSON fetched live from dokkan.wiki and cached.
- Reading the screen only — no game modification, no ToS concerns beyond
  normal screen-capture permissions.

## Results from first real screenshots (SSJ4 Goku (Mini) DAIMA, card 1032261)
- Passive-detail screen (dark bg, plain UI font): near-perfect OCR of Japanese
  prose with `jpn` + inverted grayscale + psm 6. Only digits next to icon
  badges degrade (気力+2 -> 気力T8, 250% -> ら50%).
- Card page vertical name/title (stylized outlined font, tilted ~10-15°, busy
  art bg): unreadable for Tesseract even with color-key masking, blob
  filtering, deskew sweep, and `jpn_vert` (also extracted from npm,
  `@tesseract.js-data/jpn_vert`). Stylized SA name in bottom panel: also fails.
  Plain leader-skill text next to it: partially readable.
- **Design consequence:** match on passive-screen text, not the title. Multi-
  line fuzzy voting (rapidfuzz ratio, threshold ~70, sum scores per card id)
  picked the right card against decoys 197 vs <80 despite OCR noise.
- Full identify -> fetch -> render chain validated: card page -> 1032261 ->
  dokkan.wiki API -> clean English kit.
- ML Kit (production) should handle the stylized/tilted title text much
  better than Tesseract; retest identification-by-title on-device.

## Open questions
- OCR accuracy on Dokkan's stylized title font (test with real screenshot).
- Whether card detail screens have a fixed enough layout to crop the title
  region by ratio instead of OCR-ing the whole screen.
- EZA/transformed states: same card id, different passive — the API's
  `optimal_awakening_growths` / `transformations` cover this.
