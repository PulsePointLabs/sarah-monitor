# Sarah Monitor — handoff

Where the project stands, how to work on it, and what Ben expects. Read this first in a new session.

## What this is

Sarah Monitor is the second version of Sarah: an Android physiology lab app (Kotlin, Jetpack Compose,
Material 3) used on a Samsung S25 Ultra, often in Samsung DeX. It records sessions with a Polar H10 (HR,
RR, ECG), a CIVET pelvic pressure sensor, an Omron BP cuff, MyoWare EMG, and more, and shows them as
bedside-monitor dashboards, floating overlays and a review screen.

- Repo: `PulsePointLabs/sarah-monitor`, branch `main`. Every push to `main` builds a signed release
  (GitHub Actions); it appears under Releases about 4–5 minutes later as `vX.Y.Z-main-<versionCode>`.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` with every release. Current: **0.25.1 (43)**, released from branch `rc/0.25-research-integrity` as a pre-release (main is still 0.24.2).
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
  DeX) is the best real check. Ben's Windows box (`F:\sarah-monitor`) has `sarah_phone` and `sarah_dex` (exactly 1360×700 dp).
  `tools/device-shots.ps1 [-Serial X] [-Build]` installs, grants permissions, launches and screenshots every display
  (phone + DeX) of an emulator or the real S25 (wireless debugging). Empty/no-data states only show up there.
- **Real recordings:** `./gradlew :app:testDebugUnitTest -Precordings=<folder of exported CSVs> --tests "*RecordingCheck*"`
  replays per-second CSVs through the pressure-integrity checks → `app/build/recording-check/*.txt`.
- Windows gotcha: a stray `"` in the system PATH breaks Gradle's test JVM. Until fixed: `$env:Path = $env:Path.Replace('"','')`
  and `JAVA_HOME` = Android Studio's `jbr`.

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
- `core/PelvicProcessor.kt` + `core/PressureIntegrity.kt` — CIVET pressure: logging only after the user switches it on
  (calibrate first), a `PressureQuality` on every sample, suspected displacement / shifted rest latch until the user
  recaptures rest or resumes, 5 s stabilising after a link gap, holds over 30 s = sustained elevation (not contractions).
- `session/PressureReview.kt` — the review's per-sample quality (stored live; replayed for pre-0.25 recordings;
  "Sensor problem" events exclude their range). `session/ResearchExport.kt` — research ZIP (every native sample + README).
- `docs/research/AUDIT-2026-11-23.md` — what the ChatGPT research brief claimed vs what the code did.
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

## 0.25.0 (research integrity, from the ChatGPT brief; see docs/research/AUDIT-2026-11-23.md)

- CIVET: Start logging / Stop logging; preview and calibration never count; displacement alarm with New rest / Resume;
  sensor events (connections, rest captures, zeroing, logging, faults) stored per session (DB v7, sensor_events table).
- "Sensor problem" event type in every session type (voice: "the probe slipped out"); hands-free no longer files CIVET
  notes under the Foley.
- Review: quality-aware pressure stats, fault and sustained-elevation flags, events editable with typed elapsed time,
  range, source (memory/video/observation/notes) and ± timing; Export ▾ → CSV or Research ZIP; episode progression.
- Fixed: duplicate flag titles in CSV rows; pre-session BP printed as 0:00; session-type context carrying over.

## 0.25.1 (Kegels + focus mode)

- Kegel trainer: Focus / Detail switch during a set (Detail = live 30 s graph, per-squeeze bars, tiles with trend
  arrows); a set switches pressure logging on; full-screen set review (whole-set chart with hover/drag block per
  squeeze, bar per squeeze, every squeeze up close, tiles, plain read) from the result card and from session review.
- Focus mode: top-bar button (phone: More menu) or F; Esc, X or Back exits; hides bars, monitor (or Kegel trainer) only.
- Ben's feedback (2026-11-23): he wants research-grade data visible IN the app (and server), not exports; no plain-text
  metric dumps anywhere. He will send the full ChatGPT thread; compare intent vs 0.25.0 first next session.

## 0.27.0 (talking to Sarah; one climax, then refractory)

- Wake word: the moment "Sarah" is heard she stops talking (`AudioEngine.hush`), music/video pauses
  (`audio/MediaHold`, transient audio focus, so only what was playing resumes) and one listening tone plays. One wake =
  one note: she waits 2.5 s of quiet, 5.5 s if the words trail off ("…and the"), 8 s after a bare "Sarah"; notes up to
  90 s. Media resumes after the note is saved and confirmed. Mark event dialog: same pause on dictation start, resume
  when the dialog closes. Other speech waits while you talk (dropped if stale > 20 s).
- "Sarah, end the session" / "conclude the session" / "stop recording" ends the recording (logged as an event).
  "Sarah, stop" / "never mind" logs nothing.
- ArousalEngine: a marked orgasm/ejaculation starts a 30 min refractory period: approach capped at 15, phase stays
  Recovery, no detected candidates; further orgasm/ejaculation notes are the same climax. Lifted only if he logs
  arousal/near/edge/plateau 5+ min later. Detected candidates at most one per 15 min. Walking about (H10 motion ≥ 180 mg)
  without pelvic evidence can't read as approaching.
- Sarah's voices (Style menu): Lover, Lover + body (stored as Encourage), Body state, Minimal. She now gets the
  session's devices, plan/notes, pre-session check-in and the Howl output, plus refractory/moving flags; after a climax
  she's brief and affectionate, then mostly quiet.

## 0.28.0 (procedures: numbers, running tally, questions)

- Spoken notes now carry numbers (`session/ProcedureLog.kt`, `Measures`): mL instilled (total vs added, using the
  running tally), depth in cm (inches converted), 0–10 urge / discomfort / arousal / fullness, erection state. Claude
  reads them; a keyword read fills gaps and works offline. Stored in the event's `voice.measures`; typed notes are read
  the plain way. Sarah's confirmation repeats the numbers so a mishearing gets caught.
- "Sarah, how long have I been holding?" (any question): she answers out loud from the whole session (all notes with
  times, procedure tally, live signals) in her current voice, even with live feedback off; media stays paused until
  she's done. Kept as an "Asked Sarah" event with her answer.
- Coach in body exploration: procedure partner. Knows amount in, time holding / device in place, last depth, latest
  scores and how long ago; check-ins say where things stand or ask for a score when he hasn't given one in a while.
- Review: number chips (In / Added / Depth / Urge / Discomfort / Fullness / Arousal / Erection) and Sarah's answers
  under each event. Not yet seen rendered.

## Open / unverified

- **Displacement thresholds are unvalidated** (drop ≥3 kPa below rest for 1 s, or losing 70% of preload). Run the
  recording check on the Nov 23 CSVs (`sarah-monitor-2026-11-23-0312.csv`, `…-0516(2).csv`) and tune.
- EMG already feeds the arousal engine when connected (pre-existing; brief says EMG is unvalidated). Ben to decide.
- The real S25 Ultra is not yet connected over wireless debugging; layouts verified on harness + emulators only.

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
