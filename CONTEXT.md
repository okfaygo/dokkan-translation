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
   **Same-character misses diagnosed + confidence UI (2026-08-03):**
   user reported a UR PHY card returning an LR STR of the same character,
   correct card absent from alternatives. Reproduced exactly by feeding
   ONLY the character name (stylized title/leader unread): all same-name
   cards score IDENTICALLY (49.4 each, 105 cards named 超サイヤ人孫悟空),
   so the tie-break decides — and it sorts rarity DESC, so LRs fill the
   top 4 while the correct UR sits at rank 28. 80% of the index shares a
   name with 4+ cards. Also proves badge hints aren't being OCR'd: a read
   badge would give 1.12^2 = 1.25x, mathematically enough to win.
   - IDF key weighting TRIED AND REJECTED (kept as a documented dead end
     in match.py): damping a shared name lowers the true signal while junk
     lines matching some card's rare key keep full weight, so noise wins
     relatively. Textbook log(N/n) AND a flat-below-10 variant both lost
     on every arm (name-only top-4 19->16/11, general misread 141->133).
   - SHIPPED instead — confidence surfacing: `Matcher.tiedCount()` counts
     candidates within TIE_MARGIN of the winner. Measured separation:
     median 2 on healthy card pages (1 of 80 reaching 3) vs median 7 when
     only the name is readable (51 of 60 reaching 3), so >=3 = ambiguous.
     Ambiguous results show an explicit "this may be the wrong card,
     screenshot the passive popup instead" banner + 8 alternatives
     instead of 3, and the section retitles to "Did you mean one of
     these?". Type/rarity diversification of that list was tested and NOT
     shipped (26/40 vs 29/40 plain — no ordering rescues a 105-way tie).
   - Debug panel (ui/AppScreen DebugPanel): collapsible, on both result
     and failure screens; shows the raw ML Kit lines, whether the type/
     rarity badges were read, and the top 6 candidates with scores +
     tied count. Turns a bad screenshot into data instead of guesswork.
   - Known gap: alternatives show JP titles only in the index, so an
     ambiguous list can't show English titles (the one label that would
     let a user pick their exact card visually). Would need a global-site
     scrape pass for `title_en` — ~5k requests, not done.
   **Two bugs the debug panel caught on its first real use (2026-08-03)** —
   user screenshotted UR PHY Cooler (Final Form), got UR PHY Frieza (2nd
   Form), and the panel showed Cooler scoring HIGHER (161.9 vs 160.0):
   - TIE_MARGIN was far too loose. At 0.98 anything within 2% counted as
     "tied" and the head group was re-sorted by (base, rarity, id) with
     the SCORE DISCARDED — so Frieza won on having a higher id despite
     scoring 1.2% lower. The cases the tie-break exists for (awakening
     twins, base vs its own transformed form) score EXACTLY equal, so the
     band is now 0.995; a separate AMBIGUITY_MARGIN = 0.98 keeps the
     looser band for the confidence signal only. tied_count/tiedCount now
     measure against the MAX score, not ranked.first() (the preference
     ordering can put a lower-scoring card first — that was a second,
     latent inconsistency in the ambiguity count).
   - App and prototype scored DIFFERENTLY: Kotlin scored the main and alt
     key sets separately and took max(), while match.py pools them — same
     screenshot gave 161.9 in the app vs 165.7 in the prototype, so the
     benchmarks weren't predicting app behavior. Kotlin now pools too
     (Candidate.matchedAltView deleted — dead since alt-view-by-default).
   Verified on the user's real OCR lines: Cooler 165.7 > Frieza 160.0,
   tied_count 1 (confident). Benchmarks after the tighter margin: general
   144/147/141 (was 145/147/141 — noise), hostile arm 45 vs 50 no-badge /
   86 vs 88 with badges. The synthetic hostile arm slightly prefers the
   loose band because its targets are random within near-tied groups; the
   field failure it causes is real, so the tight band ships.
   **v0.2 floating bubble scaffolded (2026-08-03, NOT device-tested):**
   overlay bubble + MediaProjection, tap-to-identify without leaving the
   game. Key API constraint that shaped the design: on Android 14+ a
   MediaProjection token is SINGLE-USE (createVirtualDisplay twice on one
   token throws; a consent Intent can be exchanged once), so a
   capture-per-tap design would prompt for consent on every tap. Instead
   one VirtualDisplay + ImageReader is created per bubble SESSION and each
   tap pulls the newest frame. Required ordering: startForeground() before
   getMediaProjection(), and a MediaProjection.Callback must be registered
   or createVirtualDisplay() throws. Android 15 QPR1+ auto-stops the
   projection on screen lock -> panel offers Resume, which re-requests
   consent through an invisible activity (consent needs an Activity; the
   bubble lives in a Service). Two non-obvious hazards handled: the
   ImageReader must be DRAINED before each capture (only 2 buffers; a full
   buffer blocks new frames, so a stale frame can be returned), and the
   bubble hides itself before capturing or it lands in its own OCR input.
   Compose in an overlay needs lifecycle/ViewModelStore/SavedStateRegistry
   owners that a Service lacks — OverlayComposeHost supplies them so the
   panel reuses the main screen's composables. The identify pipeline was
   extracted to identify/CardIdentifier so bubble and share-sheet can't
   drift. Not compiled here (no Android SDK on this machine).
   **v0.2 CONFIRMED WORKING on device (2026-08-03).** User reports accuracy
   "near 1-to-1 with both the card page AND the passive detail screen" —
   card-page accuracy is now BETTER via the bubble than via the share
   sheet, consistent with MediaProjection giving clean full-resolution
   pixels where a shared screenshot can carry compression artifacts.
   Remaining complaint: "sometimes takes a little while to fetch". First
   response is instrumentation, not guessing — per-stage timings (index /
   OCR / match / fetch) now show in the debug panel. Speculative fixes
   applied alongside: index preloaded when the bubble starts (was a ~3.5MB
   JSON parse on first tap), matching parallelised across cores
   (Matcher.rankParallel — partitioning by record is exact since records
   score independently, verified in Python: chunked merge == whole-index,
   0 differences, so ordering and benchmark parity are preserved), and the
   identified card is now named in the progress line while the network
   fetch runs. Await real timings before optimising further.
   Roadmap after that:
   v0.2 floating bubble + MediaProjection (manual trigger), v0.3 auto-detect
   card screens from captured frames (marker text or image-retrieval on
   card art — retrieval index, NOT a trained classifier). Overlay draws
   only; MediaProjection is what reads the screen (games have no
   accessibility tree).
3. EZA/transformed states: same card id, different passive — API's
   `optimal_awakening_growths` / `transformations` cover this.

No ToS concerns: screen reading only, no game modification.
