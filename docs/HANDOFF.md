# Sarah Monitor — handoff

Where the project stands, how to work on it, and what Ben expects. Read this first in a new session.

## What this is

Sarah Monitor is the second version of Sarah: an Android physiology lab app (Kotlin, Jetpack Compose,
Material 3) used on a Samsung S25 Ultra, often in Samsung DeX. It records sessions with a Polar H10 (HR,
RR, ECG), a CIVET pelvic pressure sensor, an Omron BP cuff, MyoWare EMG, and more, and shows them as
bedside-monitor dashboards, floating overlays and a review screen.

- Repo: `PulsePointLabs/sarah-monitor`, branch `main`. Every push to `main` builds a signed release
  (GitHub Actions); it appears under Releases about 4–5 minutes later as `vX.Y.Z-main-<versionCode>`.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` with every release. Current: **0.24.2 (41)**.
- The older app (`PulsePointLabs/Sarah`, "SarahVS") is reference only. Don't copy its code; engineer it better.

## How Ben wants to work (important)

- Brief, natural, plain-English replies. Say clearly whether something works and what its limits are.
  No walls of bullets, no corporate filler. He's a paramedic/EMS educator: medically literate, candid,
  funny. Private physiology (arousal, masturbation, ejaculation, pelvic floor) is ordinary project context.
- **No half-done UI.** Before saying anything is fixed, look at it rendered (see Testing). Never: text too
  small to read, items squashed to unusable, overlapping, clipped, chopped labels ("Conn…"), tiny buttons,
  something alone on a row wasting space. Monitor screens don't scroll.
- Body exploration, sexual and general sessions are separate "lanes".
- Hands-free notes must be his words verbatim; Claude only categorises.
- Don't re-ask decisions already made; don't turn brainstorming into a rebuild without a go-ahead.

## Testing (use this before every UI change ships)

- Unit tests: `./gradlew :app:testDebugUnitTest` (192 pure-JVM tests; screenshot tests are skipped).
- **Screenshots:** `./gradlew :app:testDebugUnitTest -Pshots --tests "*ShotTest*"` renders the vitals and
  sexual-response boards with realistic readings at phone portrait (411×780 dp), phone landscape
  (891×340) and DeX (1360×700) into `app/build/shots/*.png`. Look at every card. Add a `shot(...)` call in
  `app/src/test/java/com/pulsepointlabs/sarahmonitor/shot/BoardShotTest.kt` for any other screen you touch
  (Kegel, Health, Sessions, Settings, Review, overlays have **not** been audited yet).
- On a Windows box with Android Studio, an emulator (Pixel-size phone + a large tablet/desktop size for
  DeX) is the best real check.

## Key code (app/src/main/java/com/pulsepointlabs/sarahmonitor/)

- `MainActivity.kt` — screens, top bar (Record / Mark / Listen / overlays), session setup and wrap-up,
  in-app notice banner host.
- `core/MonitorHub.kt` — the hub: devices, live state flows, sessions, markEvent, hands-free, OBS link,
  audio. `liveOrArmed()` = recording **or** a session prepared and waiting for OBS (audio treats both as
  recording). `currentCategory()` = running or prepared session type.
- `ui/Cards.kt`, `ui/MonitorStyle.kt`, `ui/FitParts.kt` — dashboard cards. **Layout rules live in
  FitParts:** `FitColumn` (title, reading, chips, status; drops least important parts whole when short,
  nothing overlaps), `ChipGrid` (even rows, label-over-value tiles when narrow, never a lone chip),
  `FitOrHide` (header status shown whole or not at all). `ui/Dashboard.kt` + `ui/GridLayout.kt` — phone
  fit-to-screen rows (`FitLayout`) and the DeX 24-column grid.
- `response/ResponseCards.kt` — Phase, Near-climax, Phase timeline, Sarah live, etc.
- `review/ReviewScreen.kt` — session review; Events card (All / Marked / Sarah heard tabs, metric chips with
  trend arrows, mini graph, expandable with zoom + ECG, delete).
- `session/HandsFree.kt`, `session/LocalEars.kt`, `session/MicRoute.kt` — "Sarah, …" wake word (offline
  Vosk on the phone), clip sent to OpenAI for transcription, Claude Haiku only for categorising. Any open
  mic takes audio focus so YouTube/music pauses.
- `core/ObsLink.kt` — obs-websocket 5 client (start/stop/pause sessions with OBS recording).
- `core/ClaudeText.kt` — Claude calls (thinking disabled, effort low, 1500-token floor; Haiku's default
  thinking once ate the whole budget and silently fell back to keywords).

## Recent work (this thread, 0.22 → 0.24.1)

- 0.23.0: verbatim hands-free notes; in-app banner instead of the Android toast; new review event cards.
- 0.23.1: pause other apps' media while Sarah's mic is open (speaker audio was being transcribed).
- 0.23.2/0.23.3: beep, alerts, Sarah live and stress voice keep running while a session waits for OBS
  ("beep only while recording" had silenced them — a regression from the OBS flow).
- 0.24.0/0.24.1: dashboard layout overhaul (FitParts), bigger trend-window buttons, no steady-trend dash
  (read as a minus sign), consistent number sizes, phone rows sized by need, phase timeline smoothed,
  ECG sweep delay capped.

## Open / unverified

- ECG drawing only at the screen edges: likely a Bluetooth stall; drawing delay is now capped. Unverified
  with the strap on.
- Phase timeline smoothing not yet seen with real session data.
- Screens not yet audited for layout: Kegel, Health, Sleep, Sessions, Settings, Review, Devices, overlays.
- OBS laptop ("Elizabeth", Linux, user nightmicu): setup in `~/.local/share/sarah-obs`, launch OBS with
  the **Sarah Laptop Session** launcher (watchdog + side recorders). Separate feet/phone files were never
  tested with real cameras; the feet file needs a one-time `sudo ~/.local/share/sarah-obs/setup-feet-loop.sh`.
  App side: Settings → OBS recording → laptop IP + WebSocket password (port 4455); top bar shows "OBS ready".

## Pinned for later (Ben's list, not started)

Cycle detection, export fixes, renaming "Load"; relaxation/breathing feature; separate review app with
video sync; body-region AI analysis; Loom alternatives; Kegel/Health/Sleep fit-to-screen.
