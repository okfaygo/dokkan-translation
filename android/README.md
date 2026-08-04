# Dokkan Translate — Android

## v0.2: floating bubble (new, not yet device-tested)

Tap **Start bubble** in the app. A bubble floats over the game; tap it on
any card screen and the English kit slides up in an overlay panel — no
screenshot, no leaving Dokkan. Drag the bubble to move it; tap it again to
dismiss the panel; stop it from the notification.

Permissions it asks for, in order: *display over other apps*,
notifications (Android 13+, for the required service banner), then the
system screen-capture prompt.

**Why the architecture looks the way it does** — Android 14+ enforces that
a `MediaProjection` token is single-use: `createVirtualDisplay()` throws if
called twice on one token, and a consent Intent can only be exchanged
once. A naive capture-per-tap design would therefore show a system consent
dialog on *every tap*. So `ScreenCapture` creates ONE virtual display when
the bubble starts and keeps the screen mirrored into an `ImageReader` for
the session; each tap just pulls the newest frame. Consequences:

- The reader holds only 2 buffers, and a full buffer blocks new frames, so
  each capture **drains first**, waits ~150ms, then grabs — otherwise you
  can get a frame from minutes ago.
- The bubble hides itself before capturing, or it appears in its own OCR
  input.
- The service must call `startForeground()` **before** `getMediaProjection()`
  and must register a `MediaProjection.Callback` (`createVirtualDisplay()`
  throws without one).
- When the system stops the projection — screen lock on Android 15 QPR1+,
  or the user revoking it — the session cannot be resumed. The panel offers
  **Resume**, which re-requests consent via the invisible
  `ProjectionRequestActivity`.

Files: `bubble/BubbleService` (foreground service, bubble window, capture
orchestration), `bubble/ScreenCapture` (projection + virtual display +
frame grabs), `bubble/OverlayComposeHost` (a Service has no lifecycle /
ViewModelStore / SavedStateRegistry owners, which `ComposeView` requires —
this supplies them so the panel reuses the same composables as the main
screen), `bubble/ProjectionRequestActivity` (consent from a Service),
`ui/BubblePanel` (the sheet).

**Auto-follow (v0.3, experimental — off by default).** While the panel is
open it can watch for the game moving to another card and re-identify.
It works, but it earns its "experimental" label: flipping card-to-card is
uncommon, and each refresh flickers for ~150ms because capturing requires
hiding our own overlays first — inherent to the design, not a bug. Left in
as an opt-in toggle. Don't turn it on by default.

**Panel ergonomics (v0.3):** a *Recent* strip lists the cards identified
this session — kits are cached on disk permanently, so revisiting one is
instant and needs no capture. **Collapse** shrinks the overlay WINDOW
rather than just its contents; a full-height window with nothing drawn in
it would still swallow touches meant for the game. Collapsed, the header
shows the current card's name so it stays useful, and the next bubble tap
expands it again.

`identify/CardIdentifier` holds the screenshot → kit pipeline shared by
both the bubble and the share-sheet flow, so they cannot drift apart.

## v0.1: share sheet

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
   per card. Lines >= 14 chars also try CONTAINMENT (LCS coverage of the
   line inside a longer key, 0.95 discount) — the in-game card page shows
   leader/SA text as one TRUNCATED line, which plain ratio scores below
   threshold on ~7% of cards. Keys include SA names + passive/active names
   (plain-font on the card page) and the unwrapped leader text. The two
   views are scored separately, and the winner remembers WHICH view
   matched. Candidates within 2% of the top score are re-ranked
   base-cards-first, then rarity, then id — awakened beats unawakened, and
   a base card beats its own transformed form. The card page's type badge
   (超知/極力) and rarity emblem (UR/LR) are extracted as BOOST-ONLY hints
   (1.12x per matching hint, no mismatch penalty — misread badges must not
   sink the right card); they separate 451 of 507 same-name groups.
   Synthetic card-page benchmark: 74% -> 97% top-1; hostile
   same-character benchmark 48% -> 82% top-1 with badges.
4. `api/DokkanInfo` — fetches the GLOBAL `dokkaninfo.com/cards/<id>` page
   (embedded `datajson`), permanent disk cache under `cache/dokkaninfo/`
   (the v0.1 `cache/kits/` dir is purged on first use — its dokkan.wiki
   payloads read as blank kits under this parser). The alt view
   (`?eza=true&step=<max_eza_step>`) is the DEFAULT whenever the card has
   one — field-validated as the right kit for EZA'd cards. The &step=
   param matters: plain ?eza=true serves the wrong (untransformed) kit
   for some transformed EZA'd LR forms, and &step=<max> is a no-op
   everywhere else. No pre/post-EZA labels anywhere, deliberately.
   Replaced dokkan.wiki, which went stale.
   Passive text keeps its `{passiveImg:...}` tokens; `ui/PassiveIcons`
   renders them inline as the bundled in-game icons
   (`assets/passive_icons/`, sourced from DokkanInfo's layout assets).
   **Transformed forms of EZA'd cards** (id >= 4000000) need a second
   request: their card page — with or without `?eza=true&step=` — serves
   the BASE card's EZA passive, and the form's own EZA kit exists only at
   `/api/cards/<id>/transformation?eza=true&step=<max>` (the endpoint the
   site's own transformation arrows call). `overlayFormKit` fetches it and
   overlays passive/SA/links; leader, categories and the form list from
   the card page are already correct. Best-effort — a failure leaves the
   page kit as-is.
5. `ui/AppScreen` — Compose UI. Kit sections incl. Active Skill and a
   Transformations section (buttons jump between a card's forms), plus a
   "not the right card?" list of the next 3 candidates (English names).
   **Ambiguity:** when 3+ candidates tie within `TIE_MARGIN`
   (`Matcher.tiedCount`), the screenshot lacked card-specific text — a
   card page where only the character name was readable ties every card
   of that character. Those results show a warning banner and 8
   alternatives instead of 3. **Debug panel:** collapsible on both the
   result and failure screens — raw ML Kit lines, whether the type/rarity
   badges were read, and the top 6 candidates with scores.

## Build & run

Open this `android/` directory in Android Studio (Ladybug or newer), let it
sync, and Run on a connected phone (USB debugging on). No signing config
needed for a debug build.

CLI alternative if you have the Android SDK + Gradle installed:

```
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Keeping the index current (v0.4)

New cards would otherwise be unmatchable until someone re-scraped by hand
and rebuilt the APK. Two halves, both required:

1. **CI** (`.github/workflows/refresh-index.yml`, weekly) runs
   `build_index.py --sync`, which fetches only the ids the committed index
   is missing and commits the result. Never a full scrape — that is ~11k
   requests against someone else's server.
2. **`match/IndexUpdater`** fetches that committed index at runtime with a
   conditional request (usually a 304; ~402KB gzipped when it has actually
   changed) into internal storage, which `CardIndex` prefers over the
   bundled asset. The asset stays as the offline floor. All failures are
   silent — a missed update just means the previous index keeps working.

Without the second half, a refreshed index in the repo would sit there
doing nothing until a rebuild and reinstall.

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
