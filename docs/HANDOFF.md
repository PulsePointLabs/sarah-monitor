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
- Hands-free notes must be his words verbatim; Claude only categorizes.
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
  recaptures rest or resumes, 5 s stabilizing after a link gap, holds over 30 s = sustained elevation (not contractions).
- `session/PressureReview.kt` — the review's per-sample quality (stored live; replayed for pre-0.25 recordings;
  "Sensor problem" events exclude their range). `session/ResearchExport.kt` — research ZIP (every native sample + README).
- `docs/research/AUDIT-2026-11-23.md` — what the ChatGPT research brief claimed vs what the code did.
- `response/ResponseCards.kt` — Phase, Near-climax, Phase timeline, Sarah live, etc.
- `review/ReviewScreen.kt` — session review; Events card (All / Marked / Sarah heard tabs, metric chips with
  trend arrows, mini graph, expandable with zoom + ECG, delete).
- `session/HandsFree.kt`, `session/LocalEars.kt`, `session/MicRoute.kt` — "Sarah, …" wake word (offline
  Vosk on the phone), clip sent to OpenAI for transcription, Claude Haiku only for categorizing. Any open
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

## 0.28.1 (American)

- US spelling throughout (color, gray, analyzed, canceled, categorize…). Ben is American: keep it that way.
- US units for everything he sees or hears: depth in inches, volumes in fl oz / quarts (cc under 4 oz), via
  `session/UsUnits`. Stored numbers stay mL / cm (one unit for research data). Speech accepts oz, quarts, pints, cups,
  cc, inches (and metric). Device fields: balloon/lube in cc, depths in inches, enema volume in oz; old metric values
  still show on old sessions. Sarah's prompts say he's American.
- 0.28.2: pelvic pressure shown/spoken in mmHg everywhere (`core/Pressure`; storage, thresholds and research
  columns stay kPa, research CSV gains above_rest_mmhg, sensor-event text keeps kPa alongside). 12-hour clocks
  everywhere (top bar h:mm a). More US spellings (labeled, modeled, neighbor, math).
- 0.28.2 sorting: every event type has a definition sent to Claude (`MarkerTypes.Type.hint`); new types Substance
  (cannabis/alcohol/caffeine/nicotine/medication, with what + how much + 0–10 "feeling it") and How I feel (mood /
  energy), offered in every session type with Symptom. Recovery = physiological recovery only. Keyword fallback
  knows substances, feelings, activities. Review shows substance chips.

## 0.29.0 (Sarah talks again)

- `response/SarahChat.kt`: Sarah replies in her own words after every hands-free note ("Sounds good, take the
  chores slow"; repeats any numbers) and answers questions / chat with context: local time, session, devices,
  procedure tally, live HR/HRV/stress, this session's notes, and `SarahMemory` (last 48 h of what he said and what
  she said, persisted on the phone). Personality written fresh in the spirit of v1's "warm clinical" defaults
  (PulsePointLabs/Sarah src/utils/sarahPersonality.js, src/lib/intimateChatTone.js, chatConversationPrompt.js; read,
  not copied): feminine warmth, plain English, evidence-first, continuity (earlier ≠ still happening), no forced
  follow-ups. Voice closeness follows the Style menu. Uses the chosen coach model through ClaudeText.
- Settings → Hands-free → "Sarah talks with you" (on by default; off = old plain confirmations).
- Question detection: Claude's flag OR worded as a question (his "Are you currently able to tell me…" had been
  filed as Event).
- Not done: v1's conversation database (not on this PC; v1 server data lives elsewhere), v1 language profile /
  correction memory, custom-instructions field.

## 0.29.1 (never interrupt a conversation)

- `audio/OtherAudio`: polls every 1 s for a phone call (audio mode), a voice chat (MODE_IN_COMMUNICATION or someone
  else's voice-communication playback) or another app recording through the mic (any capture session that isn't
  ours; our own AudioRecords register their session ids; hotword source 1999 ignored). While busy: Sarah doesn't speak
  (lines wait 20 s then drop), no beeps/tones/cues, alerts only vibrate, coach doesn't think, MediaHold/MicRoute
  take no focus, and hands-free listening stops ("Paused: voice chat going on") and restarts after.
- Bug found: always-on wake listening (LocalEars) went through MicRoute.open, which took permanent AUDIOFOCUS_GAIN
  for the whole session. Now `holdMedia = false`; media pauses only on "Sarah" (MediaHold).
- Unverified on the phone: whether Claude/ChatGPT voice mode shows up as IN_COMMUNICATION / a recording config on
  One UI (it should), and whether a camera video recording should also silence her (it does now).

## 0.30.0 (overlays, event panel, keyboard, layout sweep)

- Overlays (`overlay/OverlayManager`, `OverlaySnap`, `TinyOverlay`): each overlay window gets every touch through a
  `TouchFrame` (Compose would swallow them) and works in raw screen coordinates. Drag = magnetic snap to neighbors
  (flush with a 4 dp gap, top/bottom/left/right aligned) and screen edges, else an 8 dp grid; release never leaves
  it on another overlay (`settle`). Corner grip resizes with edge/size snapping and stops at neighbors. Drag onto the
  circle that appears at the bottom to close; no ✕. Long-press ＋ = menu (hide/show other overlays, mark event, open
  app). Layout saved per profile (phone/DeX) and per orientation (port/land); overlays never start stacked.
  Coordinates use `OverlayDisplay.overlayArea` (display minus bars and cutout), not the full display.
- Cards below 170×112 dp become a compact strip (label, number, arrows, extras, mini trend); min size 76×44 dp.
- Trends everywhere: light smoothing + quadratic curve; the line and a soft fill take the metric's own value colors
  (`metricBand`) as a vertical gradient. Overlay chips use the even `ChipGrid`; chips can show trend arrows
  (`rememberTrend` for values without history: stress parts, load parts).
- Mark event panel rebuilt: note + big mic first, "What kind" chips by session (`EventChoices`: general = activity,
  how I feel, substance, intake, symptom, position, other; Recovery hidden; Sensor problem only with the CIVET on),
  inline feeling and 0–10 stress chips (no dropdowns), device chips, live readings last (hidden if nothing is
  connected). In-app it's a full-screen Dialog with safeDrawing insets (keyboard never covers it).
- Keyboard: MainActivity had no windowSoftInputMode (only MarkEventActivity did), so Android panned the window and
  left a black gap. Now adjustResize + enableEdgeToEdge (light bar icons).
- Layout sweep on the sarah_phone emulator, portrait and landscape: review header actions wrap (Delete was being
  squeezed to vertical letters), `ChoiceRow` = `EvenGrid` (no lone chip, never drops a choice), session card says
  why the HR line is empty, Sarah card shows "General" in general sessions.

## 0.31.0 (Gear catalog)

- New **Gear** tab (`gear/GearCatalog.kt`, `gear/GearScreen.kt`): his devices and supplies by category (sounds,
  catheters, enema, anal, sleeves, vibrators, e-stim/TENS, sensors, lube, other). Photos (camera via FileProvider
  cache/shared, or the photo picker) are downscaled to 1600 px and kept in filesDir/gear/photos; the catalog is
  filesDir/gear/catalog.json. "Let Sarah fill this in" sends up to 4 photos + what he said to the coach model
  (`ClaudeText.askWithImages`) and fills category, name, measurements in US units (only from his words, labels or a
  visible scale; photo reads marked "≈ estimated"), material, what it is, where it sits, prep (incl. enema prep),
  cautions and a line from Sarah. His own numbers always win. Detail view: photos, measurement table, sections,
  "Ask Sarah about this". SarahChat gets the catalog (`his_gear_catalog`) so naming an item mid-session means something.
- Overlays step aside while the camera or photo picker is open (`OverlaySettings.lent`): the Stress overlay covered the
  camera shutter.
- Untested: Sarah's photo reading (no API key on the emulator).

## 0.31.1 (Load: motion stream stalls)

- Load showed "H10 motion stream hasn't started" while ECG ran. The PMD chain (ECG start → ACC settings query → ACC
  start) writes the next command from inside the strap's reply; when the previous write's callback hadn't landed yet,
  `writeCharacteristic` returned false and nothing retried, so motion sat in Configuring all session. Now `writePmd`
  retries refused writes (12 × 250 ms); `motionWatchdog` (on every ECG frame) restarts motion setup and resets its
  parser clock if no motion sample was accepted for 12 s while ECG streams (max 6 per link, logged as a breadcrumb);
  an "already in state" (6) reply to ACC start stops then re-starts motion.
- Unverified on the strap (no H10 on the emulator).

## 0.32.0 (Voice notes, Sarah's personality, Settings pages)

- **No more mid-sentence cut-offs** (`LocalEars`): the noise floor no longer creeps up toward his voice while a
  note is being taken (it was turning long speech into "quiet"); any new recognized word resets the quiet clock;
  notes of 20+ words get 3.5 s to pause and think; steady sound with no new words for need + 6 s ends a note (fan/TV).
  The 90 s cap is now a 5 min safety net only. Pure `phraseOver()` is unit-tested.
- **Stumbles filtered**: the classifier returns a `clean` dictation-style copy (fillers, stammers, false starts
  removed). `VoiceCommand.acceptClean` only accepts it if it deleted words in order (no rewording, no summary);
  otherwise `tidy()` does a local cleanup. Raw words are still saved in the voice JSON (`raw`).
- **Replies to notes**: acknowledge the specific thing, then one useful link to the session so far (trends, hold
  time, earlier plans, readings that moved), fit to the session type; no generic encouragement; `your_last_replies`
  keeps her from repeating herself. Timeline entries now carry clock times.
- **Sarah's personality** (`SarahPersonality`, Settings → Sarah): v1's tone presets (warm clinical default, soft
  feminine, clinical direct, technical deep), detail level, four style switches and his standing instructions with
  the v1 default text. Added to chat, answers and live-feedback prompts. "Hear how she sounds" makes one short call.
  Her brain (model) moved here from live feedback.
- **Settings reorganized into pages**: Quick, Sarah, Overlays, Sound & alerts, Devices, Recording & keys. Wide
  windows get a side menu (quick switches under it) and the page's cards packed into up to 3 columns (`Masonry`);
  phone portrait gets a two-row tab grid. Hands-free moved out of "Speech to text" into its own card. Overlays page =
  Floating overlays / Which overlays / Look & feel. Checked on phone portrait + landscape, DeX emulator full screen
  and a 1500×1100 window. Not checked on the real S25 or real DeX.

## 0.32.1 (BP cuff, BP per session, stress card)

- **Cuff didn't connect until re-paired**: while armed, the app only reconnected to the saved address; the cuff can
  come back under a new Bluetooth address. Now a low-power scan for the blood-pressure service runs while waiting
  (paused 20 s after a reading, restarted every 10 min) and connects to the cuff at whatever address it uses. A
  `linkUp` guard stops retries/the watch from opening a new link over a live transfer (the old 5 s retry could).
  Unverified with the real cuff.
- **BP only from the current session** (`duringSession`): card, overlay strip, event snapshots and the notification
  show nothing when not recording and "--/--" until a reading is taken in this session. Note: the cuff's own clock
  stamps readings; a badly-set cuff clock could hide a fresh reading.
- **Stress drivers keep fixed slots** (`StressComponent.slots`): HRV, Heart, Breathing, Tension, Restless always
  shown, "--" and a level trend mark when missing; the overlay strip shows HRV/Heart/Breath in fixed order.
- Seen on the phone emulator: a ~12 s main-thread stall at launch (738 skipped frames) with its 25-hour test
  recording running. Pre-existing (also slow before this change); not investigated.

## 0.32.2

- Health panels: the title gets its width before the trailing chip row (it was squeezed to vertical letters on DeX).

## 0.33.0 (Settings tabs, personality switch, sample-reply fix, release notes)

- **Settings, take two**: Ben's DeX screenshot showed ragged card columns and black holes (Masonry of whole cards).
  Now each page has a tab per section; the chosen section fills the width and its own rows flow into balanced
  newspaper columns (`ColumnFlow` + `partition`, up to 3 on DeX, 2 in phone landscape, 1 in portrait).
  `SettingGroup` keeps a label with its control across a column break. Overlays are one section again.
  Checked: DeX emulator (Sarah, Overlays, Alerts), phone portrait and landscape (Sarah).
- **"Hear how she sounds" said the API key was wrong**: that message was a guess. Replies over the word cap were
  thrown away (null), which the sample reported as a key problem. Now `SarahChat.fitWords` cuts to whole
  sentences, and failures show the real reason (`SarahChat.problem` / `ClaudeText.lastProblem`).
- **Personality master switch** (`SarahPersonality.enabled`, default on): off = no personality block in any prompt;
  settings are kept.
- **Releases**: `release-apk.yml` now uses the full commit message as the release notes and marks main and rc/*
  builds as Latest (other branches stay test builds).

## 0.33.1 (Opus 400 fix, instructions switch, Tension only with a sensor)

- **400 "thinking.type.disabled is not supported"**: `ClaudeText.ask`/`askWithImages` sent thinking=disabled to
  every model; Opus 5.5 rejects it. Every Opus reply (hands-free check-ins via SarahChat, the sample, gear photo
  reads) failed and fell back. Now only Haiku gets it (`allowsDisabledThinking`).
- **"Use my instructions to her"** switch (`SarahPersonality.useInstructions`); the text is kept when off.
- **Column breaks stay put** (`ColumnFlow` memo): they re-balance only when rows appear/disappear or the column
  count changes, not when something grows (the sample reply moved the instructions to another column).
- **Tension** shows only while the CIVET (connected/reconnecting) or EMG (USB link up) is in use (`tensionInUse`),
  on the card and the overlay strip; otherwise the other four drivers share the row.

## 0.33.2 (connection check, full Settings sweep fixes)

- **"Check Sarah's connections"** (Sarah → Personality, and Recording & keys → API keys): `SarahSelfCheck` makes
  each real Claude call with his keys (note sorting on Haiku, her replies, her answers) and lists pass/fail in plain
  words. The emulator and unit tests can't do this (keys live only on his phone).
- Full sweep of every Settings page and tab on DeX, phone portrait and phone landscape (scratchpad `sweep.ps1`,
  contact sheets in `app/build/device-shots/sweep`). Fixed what it found:
  - short sections (H10, CIVET, Omron, Glucose) were split into scattered columns: under 5 rows = one column;
  - tabs ran off the edge in portrait: they wrap now;
  - phone landscape menu needed scrolling: compact (no blurbs) when the window is under 560 dp tall;
  - the live overlay preview covered most of the page in landscape and caught taps: it now shows only on the
    Overlays page.

## 0.34.0 (stability, categorization, no pet names, faster replies, wake word)

- **Release builds now**: CI publishes `assembleRelease` (same key, minify off, installs over the debug build with
  data kept). Emulator cold start 4.4 s → 0.9 s; hub init 440 → 40 ms.
- **Crashes**: `stopSession`/`markEvent` callbacks ran on a background thread and changed screens (possible
  "Unsupported concurrent change" crash on ending a session): now on Main. `CrashLog` writes any crash to a file;
  next launch shows "The app closed unexpectedly… Copy details". Session setup dialog was under the status bar
  ("Start now" untappable): now safeDrawing-padded. Start / reopen mid-session / stop / crash-note checked on the
  phone emulator.
- **Notes filed as Symptom**: sexual sessions didn't offer "event", so the fallback was the first offered type
  (Symptom). "event" is offered everywhere and is the fallback; the classifier is told the session type and when
  Symptom applies.
- **Pet names**: `SarahPersonality.petNames` (off by default) + `addressBlock` (always sent: no pet names, his name
  rarely) + `cleanAddress` post-filter on chat and coach lines (removes vocative pet names / his name only).
- **Latency**: note sorting runs in parallel with her reply (`scope.async`); she speaks first, the note is saved
  after. Gear catalog moved into the cached system prompt.
- **Wake word**: second Vosk recogniser with a name-only grammar (`WAKE_GRAMMAR`, `grammarWake`) between notes;
  more spellings in `WAKE`. End of thought: 1.8 s after a clearly finished short note (closing word or number).
  Unverified with a real voice.

## 0.34.1 (no unasked advice, stray tones)

- Her notes and answers no longer give unasked advice, warnings or disclaimers ("eat something, don't drive"):
  rule in `SarahPersonality.addressBlock` (always sent); SarahChat's "sensible next step or caution" became "a natural
  follow-up question", and the substances line no longer invites medical warnings. Red-flag answers to questions he
  asks (SarahCoach ANSWER_PROMPT) are unchanged.
- Stray listening tones after a note: the 0.34 name-only recogniser could match Sarah's own voice from the speaker
  (a wake hushes her and plays the tones). It's now deaf while she speaks, while a note is being handled, and for
  1.5 s after (`nameOnlyAllowed`, `NAME_ONLY_TAIL_MS`). The full recogniser still lets "Sarah" interrupt her.

## 0.35.0 (finger move/resize on DeX, one graph style, ECG sweep, wake on cough, pelvic floor, new overlays)

- **DeX dashboard touch**: per-card gesture handlers tracked the finger in the card's own (moving) coordinates, so
  the card's motion cancelled the finger's: moves stuttered, resizes barely moved. One board-level handler now
  (`GridDashboard`): arranging = drag to move, right edge (width) / bottom edge (height) / corner (both), 30 dp
  grips; not arranging = long-press picks up. Verified on the DeX emulator with adb touch input.
- **One graph style**: `TrendChart` (big trend cards) now smooths and curves lines with a color-matched fading fill
  and a vertical grid; `TrendLine` (in-card sparklines) fills the remaining card space, has a faint grid and hi/lo
  labels; `drawSmoothTrend` fill fades toward the bottom. Big numbers with a trend take `trendColor` everywhere.
- **ECG only one beat**: the sweep compared the phone clock to strap timestamps; with the strap clock ahead, newer
  samples were skipped. It now keeps time in the strap's clock (`SweepState.skewMs`). Unverified with the strap.
- **Wake on cough**: the name-only recogniser needs 3 consecutive hits (~0.3 s) within 350 ms+ of speech.
- **Tension → "Pelvic floor"**: always shown while a CIVET is set up (or the EMG has streamed), no flicker on
  reconnects; shown but not counted toward stress in sexual/Kegel sessions (`tensionCounts`).
- **New overlays**: Arousal phase, Near-climax watch, Breathing & motion, EMG, ECG.

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
