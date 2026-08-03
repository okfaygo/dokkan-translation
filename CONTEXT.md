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
   **Field-test round 4 (2026-07-31, untested on device yet):**
   - User decision (validated in the field): the alt (?eza=true) view is
     the DEFAULT whenever a card has one — no matched-view logic. The
     "SEZA'd LRs would show pre-SEZA kits" concern did not materialize.
   - User finding: plain ?eza=true serves the wrong (untransformed) kit
     for some transformed EZA'd LR forms; `?eza=true&step=<max>` is
     required there (step=3 EZA'd LRs, step=4 SEZA'd). `max_eza_step` is
     a top-level datajson field on every card page (LR=3, UR=7 etc.), so:
     scraper now fetches alt views with &step=<max> (`--refresh-alt`
     upgrades an old cache), index carries `eza_step` per card, app
     appends &step= when fetching the alt view. Verified &step=<max> is a
     no-op where plain ?eza=true was already correct; step>max = HTTP 500.
   - Passive icons: {passiveImg:...} tokens (10 keys: up_g/down_r/down_y/
     down_g arrows, once/forever badges, stun/atk_down/def_down/astute
     status icons) are no longer stripped — rendered inline via Compose
     InlineTextContent. PNGs bundled at assets/passive_icons/, pulled from
     dokkaninfo.com/assets/global/en/layout/en/image/ingame/battle/
     skill_dialog/ (mapping recovered from the site's app.js).
   **Field-test round 5 (2026-08-02):** transformed forms of EZA'd cards
   showed the wrong kit AND couldn't be identified. Root cause: a
   transformed form's card page serves the BASE card's EZA passive at
   every URL variant (bare, ?eza=true, ?eza=true&step=N). The form's own
   EZA kit exists ONLY at the JSON API
   `/api/cards/<id>/transformation?eza=true&step=<max>` — found by reading
   dokkaninfo's app.js (its transformation arrows call it). Proof:
   4019411 page -> passive #3933 (= base 1019401's EZA kit); API -> #3934
   ("Recovers 50% HP, Ki +4, ATK & DEF 200%"), the form's real EZA kit.
   Round-4's `&step=` was a no-op for forms — it only fixes base cards.
   Fixed BOTH sides: app overlays the API's passive/SA/links onto the page
   kit (leader/categories/form-list are already right); scraper fetches
   form alt lines from the API so those ~119 forms become matchable at all
   (their index text was previously the base card's).
   API payload has only 9 keys — no leader_skill/categories/
   transformations — hence the overlay rather than a straight swap.
   `?eza=false` on that endpoint is a 500; un-EZA'd forms use the bare
   card page, which is already correct.
   **Card-page accuracy round (2026-08-02):** passive-screen input was
   near-perfect; card-page input was the weak spot. Diagnosed causes:
   (1) the in-game card page TRUNCATES leader/SA text to one line
   (median best-match score 93, 7% of cards fully invisible at the 70
   threshold); (2) SA names — plain-font and distinctive on the card
   page — weren't in the index at all; (3) passive/active names were
   stored but unused as keys. Fixes: `sa_names` added to the index
   (5,161 records, incl. (極限) EZA variants from alt pages);
   passive_name/active_name/unwrapped-leader added to match keys;
   CONTAINMENT scoring (LCS coverage of the OCR line, 0.95 discount,
   only for lines >= 14 chars vs longer keys — the length floor stops
   category chips lighting up every kit mentioning them; derived from
   fuzz.ratio with no extra computation: coverage = ratio*(m+n)/(2m)).
   Synthetic card-page benchmark, 150 cards: 74% -> 97% top-1.
   Passive-screen cost: 84->82 of 100 on an exact-lines sweep whose
   misses are pre-existing same-character near-duplicates (right card
   still in alternatives). Kotlin Matcher mirrors exactly (LCS computed
   once, ratio+coverage derived; prefilter bypassed for containment).
   Next accuracy instrument if field results still disappoint: a debug
   view exposing raw ML Kit lines so failures become tunable data.
   **Badge hints (2026-08-02, after field report of a same-character
   miss: UR PHY input -> LR STR output, right card not in alternatives):**
   the card page's type badge (超知/極力 etc.) and rarity emblem (UR/LR)
   are 1-3 char OCR lines that never vote — now extracted as hints
   (only unambiguous forms: 超X/極X, exact UR/LR/SSR; OcrEngine keeps
   rarity-only lines that the JP filter used to drop). BOOST-ONLY 1.12x
   per matching hint, no mismatch penalty — benchmarked (prototype/
   bench.py, vectorized cdist harness): penalty variant scores 105/120
   vs boost-only 98/120 on hostile same-character cases (baseline 57),
   but collapses 148->108/150 when both badges misread vs boost-only's
   141. Type/rarity separates 451 of 507 same-name groups.
   Roadmap after that:
   v0.2 floating bubble + MediaProjection (manual trigger), v0.3 auto-detect
   card screens from captured frames (marker text or image-retrieval on
   card art — retrieval index, NOT a trained classifier). Overlay draws
   only; MediaProjection is what reads the screen (games have no
   accessibility tree).
3. EZA/transformed states: same card id, different passive — API's
   `optimal_awakening_growths` / `transformations` cover this.

No ToS concerns: screen reading only, no game modification.
