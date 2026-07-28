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
- ~~DokkanInfo is client-rendered (empty HTML shell for scrapers)~~ —
  **outdated (re-checked 2026-07-28)**: card pages are server-rendered with
  the full kit embedded as HTML-entity-escaped JSON. Language/server variants
  live on subdomains: `jpnja.dokkaninfo.com` (JP server, Japanese text — JP
  title + name + leader + the exact passive-detail lines we OCR),
  `jpnen.dokkaninfo.com` (JP server, English). The card list
  `jpnja.dokkaninfo.com/cards?sort=open_at` is a single 12MB page embedding a
  JSON array of all ~11,176 cards (id, JP name, rarity, eza, open_at) — the
  enumeration source for the index scrape. Requires a real browser
  User-Agent (default curl UA -> Cloudflare 403). No XHR involved, plain
  curl works.
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

## Index scraper (build_index.py, validated 2026-07-28)

- 20-card stratified sample (all rarities, EZAs, oldest/newest, transforming
  cards) scraped and validated end to end.
- Card page structure: `datajson` attr -> `card` (id, JP name),
  `leader_skill.name` **is the card title** (the near-unique matching key),
  `leader_skill.description`, `passive_skill.itemized_description` = exactly
  the passive-detail-screen lines we OCR (after stripping `{passiveImg:..}`
  markup, `*headers*`, `・` bullets, full-width spaces).
- **EZA**: bare `/cards/<id>` serves the CURRENT max-EZA/SEZA kit;
  `/cards/<id>?eza=true` (counterintuitively) serves the ORIGINAL pre-EZA
  kit. Detect EZA via non-empty `eza_medals` in datajson; index both line
  sets (`lines` + `pre_eza_lines`). Intermediate EZA steps (partially-EZA'd
  cards) are not covered — acceptable gap, fuzzy voting still overlaps.
- **Transformations**: `transformations` in datajson lists the other forms'
  ids (4xxxxxx etc.); scraper follows them recursively. dokkan.wiki API
  serves those ids too, so transformed forms resolve to EN kits directly.
- **Filler**: many `9xxxxxx` list entries (World Tournament/event units)
  have no leader/passive at all — skipped (no title AND no lines). Some
  9xxxxxx story cards (e.g. giant-form Vegeta) are real and kept.
- List page rarity can disagree with card page rarity (e.g. 1005701 listed
  SSR, page says SR) — cosmetic, ids are what matter.
- Matching (match.py, rewritten for the rich index): per-OCR-line best
  rapidfuzz ratio >= 70 vs each card's lines/title/name, summed per card.
  Smoke test with heavy synthetic OCR garbling (気力+2->気力T8 etc.):
  1032261 wins 741 vs 346 runner-up; pre-EZA Cell lines -> 1017351 at
  992 vs 526. ID alignment with dokkan.wiki EN API spot-checked on 3 cards
  including a transformed form.
- Full run: ~11k SSR+ cards x 2 requests for the ~656 EZA'd ones, 1 req/s
  -> ~3.5h one-time; cache dir holds gzipped datajson (~80KB/card).

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
