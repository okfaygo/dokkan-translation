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
2. Android app (Kotlin): **v0.1 scaffold built (2026-07-29)** in `android/`
   — share-sheet MVP (screenshot -> Share -> English kit; no special
   permissions), ML Kit Text Recognition v2 (Japanese, bundled model),
   bundled `assets/index.json`, Kotlin port of the voting matcher (LCS
   ratio verified identical to rapidfuzz fuzz.ratio), dokkan.wiki fetch +
   disk cache, Compose UI with alternative-candidate fallback.
   **Validated on device (2026-07-29): real passive-screen screenshot of
   SSJ4 Goku (Mini) DAIMA -> correct English kit, end to end.**
   **Field-test round 1 fixes (2026-07-29, untested on device yet):**
   - EN kit source switched dokkan.wiki -> GLOBAL dokkaninfo.com datajson
     (dokkan.wiki went stale, 404s on recent cards like 1034341; dokkandb.com
     was evaluated but is client-rendered with no visible API). `?eza=true`
     = pre-EZA kit, same as the JP scrape.
   - Card-page screenshots now identifiable: leader-skill lines added to
     match keys (the one plain-font element on the card page); votes are
     length-weighted so category chips/UI labels can't swamp real passive
     lines. Simulated card-page test: was 0 matches, now correct card #1.
   - EZA state: pre/post key groups scored separately; the matched state
     picks which kit to fetch, plus a manual pre/post toggle in the UI.
   - Alternatives list shows English names (merged from the GLOBAL list
     page into index.json as `name_en`, 5,141/5,169 covered).
   - Itemized-passive renderer rewritten: *headers* spanning wrapped lines
     and "- "/"・" items with continuations now render as clean rows.
   **Field-test round 2 fixes (2026-07-30, untested on device yet)** — from
   the user's 10-slide field report:
   - Blank kits root-caused: v0.1's dokkan.wiki cache files collided with
     v0.2's cache filenames (same `cache/kits/<id>.json` path, different
     schema -> blank leader/passive, Categories-but-no-Links fingerprint).
     New cache dir `cache/dokkaninfo/`, legacy dir purged on first use.
   - EZA toggle SCRAPPED (user decision + mislabels): EZA is multi-step
     (EZA -> SEZA), `?eza=true` = previous step not "original", and the
     binary label was wrong whenever a card sat mid-chain. Always show the
     current max kit now; previous-step lines kept in the index for
     matching only (they correctly identified a mid-EZA card in the field).
   - Transformations section: buttons in the kit view jump between forms
     (ids+names from the payload's `transformations` list).
   - Awakening-sibling tie-break: candidates within 2% of top score
     re-ranked by rarity then id (field report slide 10: awakened card
     matched its unawakened sibling on shared text).
   - Remaining known issue (own work item): card-page identification
     accuracy on transformed forms / busy screens; needs OCR-line dumps
     from failing screenshots (debug screen) before tuning.
   **Field-test round 3 fixes (2026-07-30, untested on device yet):**
   - KEY DATA FINDING: DokkanInfo's two per-card views are NOT consistently
     base/current — bare URL = base kit for EZA'd URs (e.g. Majin Vegeta
     1023981, both jpnja and global) but SEZA kit for LRs (Cell 1017351);
     `?eza=true` is the respective other. So "always fetch bare" (round 2)
     showed base kits for EZA'd URs. Display rule now: fetch whichever view
     MATCHED the screenshot (the screenshot is ground truth for the
     player's state). Index stores both views' lines ("lines" = bare view,
     "pre_eza_lines" = ?eza=true view — historical name, do NOT read it as
     literally pre-EZA). No pre/post labels or toggles anywhere.
   - Tie-break regression fix: round 2's id tie-break made transformed
     forms (4xxxxxxx > 1xxxxxxx) beat their base cards; head-group order is
     now base-cards-first, then rarity, then id.
   - Alternatives list shows "[UR Extreme INT] Name" labels (rarity +
     element from the index) to disambiguate same-name cards.
   Roadmap after that:
   v0.2 floating bubble + MediaProjection (manual trigger), v0.3 auto-detect
   card screens from captured frames (marker text or image-retrieval on
   card art — retrieval index, NOT a trained classifier). Overlay draws
   only; MediaProjection is what reads the screen (games have no
   accessibility tree).
3. EZA/transformed states: same card id, different passive — API's
   `optimal_awakening_growths` / `transformations` cover this.

No ToS concerns: screen reading only, no game modification.
