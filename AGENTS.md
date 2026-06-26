# Agent Guide

## Goal

Build **PomoTick**, a lightweight tomato timer for watches, with **OPPO Watch 4 Pro** as the primary device.

Primary device:

- OPPO Watch 4 Pro (model `OWW221`)
- ColorOS Watch V7.1
- Android 11 / API 30
- 1.91" LTPO AMOLED curved rectangular panel
- Resolution: 378 × 496 px (≈ 189 × 248 dp at xhdpi)
- Visible area: round display area inscribed in the 189 × 248 dp rectangle;
  keep critical UI within the inscribed safe circle = min(short side) − 2 × inset
  (OWW221: 189 − 2 × 12 = ~165 dp diameter), centered on screen.

Optimize for: reliable timing, strong but bounded reminders, and simple watch-first operation.

## Product Principles

- Do not build a phone-style productivity app.
- Keep the product lightweight, focused, and usable on a small watch screen.
- Prioritize the timer, reminder response, settings, stats, and lightweight action surfaces.
- Core actions must be easy to discover and easy to trigger on a watch.
- Avoid dense information layouts, deep navigation, and phone-first workflows.
- Specific feature scope belongs in the current version requirements document, not in this guide.

## Version Requirements

- Each release or feature revision should have its own Markdown requirements document.
- Read the current version requirements document before implementing version-specific behavior.
- `AGENTS.md` defines long-term product, technical, and safety boundaries.
- Version requirements define the current release's features, interactions, settings, stats, reminder parameters, and acceptance criteria.
- If a version requirement conflicts with the long-term constraints in this guide, keep the long-term constraint unless the exception is explicitly documented.
- Do not permanently encode one release's exact feature set into `AGENTS.md`.

## Technical Rules

- Use Kotlin.
- Use a single app module unless a version requirement explicitly justifies otherwise.
- `minSdk = 30`.
- Do not depend on Google Play Services for core behavior.
- Set `android.hardware.type.watch` as `required=false`.
- Use Room for completed timer sessions.
- Use DataStore or a single runtime-state table for current timer state.
- Do not use Hilt, Dagger, or dependency-injection frameworks.
- Do not use complex Navigation frameworks. Prefer simple local screen state.
- Do not add complex charts, extra modules, or architecture layers unless explicitly requested by a version requirements document.
- Use SDK 30+ compatible modern APIs. Do not generate deprecated legacy code.
- For vibration, use `VibrationEffect.createWaveform()` on API 30+.
- Prefer standard Compose layout primitives such as `Column`, `Row`, `Box`, and `LazyColumn`.
- Avoid round-watch-first Wear OS components unless they are clearly needed.

## Timer Rules

- Do not use a per-second loop as the source of truth.
- Store and calculate from timestamps:
  - `startedAtEpochMillis`
  - `targetEndAtEpochMillis`
  - `pausedAtEpochMillis`
  - `accumulatedPausedMillis`
- UI may refresh every second while visible.
- Background service may check periodically, but real remaining time comes from timestamps.
- Never persist only "remaining seconds" as the recovery source.
- Pause stops focus time from accumulating.
- Resume shifts `targetEndAtEpochMillis` by paused duration.
- Timer completion, early finish, abandon, reset, extension, and phase switching behavior should be defined by the active version requirements document.

## Startup Recovery

On app start, load persisted runtime state and compare `now` with `targetEndAtEpochMillis`.

- No runtime state: show idle timer.
- `RUNNING` and `now < targetEndAtEpochMillis`: restore active countdown.
- `RUNNING` and `now >= targetEndAtEpochMillis`: enter `RINGING` and show reminder response.
- `PAUSED`: restore paused screen using `pausedAtEpochMillis`.
- `RINGING`: show reminder response and resume bounded reminder behavior if needed.
- `FINISHED` or stale completed state: clear runtime state and show idle timer.

## State Model

Use separate concepts:

- `TimerPhase`: `FOCUS`, `SHORT_BREAK`, `LONG_BREAK`
- `TimerRunState`: `IDLE`, `RUNNING`, `PAUSED`, `RINGING`, `FINISHED`

Important behavior:

- Phase describes what kind of timer is active.
- Run state describes what the timer is currently doing.
- Reminder response must not be modeled as a phase.
- Completed sessions should record enough information to distinguish completed, early-finished, and interrupted sessions.
- Specific phase sequence rules belong in the active version requirements document.

## Reminder Principles

- Use `ForegroundService` for active timers.
- Do not use WorkManager for exact timer completion.
- On OPPO Watch 4 Pro, verify background behavior on real hardware.
- Guide user to set battery management to "unrestricted" if needed.
- On timer completion, enter `RINGING`, show reminder response, and trigger the configured reminder behavior.
- Reminders must be strong but bounded.
- Stop sound and vibration immediately after user response.
- Reminder auto-stop duration, repeat count, repeat interval, sound behavior, and vibration strength are version-specific requirements.
- If a version does not define reminder repeat behavior, use a conservative bounded default: one reminder, automatic stop, no infinite loop.

## UI Principles

- Design for watch-first operation.
- OPPO Watch 4 Pro **rectangular** 189 × 248 dp screen with a **round visible area** (~224 dp diameter circle) is the primary layout target.
- Always read actual screen size via `BoxWithConstraints`. Never assume a fixed canvas (410 × 410 or otherwise). Use `min(maxWidth, maxHeight) − 24.dp` as the round safe-area diameter.
- Keep critical UI inside the inscribed circle. Anything in the four corners of the rectangle will be clipped by the curved glass.
- Layouts should still avoid clipping and unsafe edge placement on round screens.
- Use large, easy-to-tap primary interaction areas.
- Avoid crowding the main timer screen with many small persistent buttons.
- Gestures, taps, long presses, and compact action surfaces may be used when defined by the version requirements document.
- Keep screens visually simple and focused on the current task.
- Text must not overlap, clip, or sit too close to the screen edge.
- Do not over-design with decorative layouts that reduce readability or tap reliability.

## Data And Stats Principles

- Use Room as the durable source for completed timer sessions.
- Store enough data to recover useful stats without relying on UI-only calculations.
- Stats should be lightweight, watch-readable, and aligned with the current version requirements.
- Do not add complex chart libraries unless a version requirements document explicitly asks for them.
- Prefer simple summaries, compact lists, and small visual indicators that work on a watch screen.

## Reference Projects

- `nsh07/Tomato` is reference only.
- Do not copy Tomato code by default because it is GPL-3.0.
- Borrow ideas, not implementation.

## Test Priorities

Always prioritize these checks:

- Timer accuracy after a full focus duration.
- Pause/resume correctness.
- App restart recovery.
- Screen-off completion reminder.
- Background completion reminder.
- Reminder stop after user response.
- Runtime state recovery for `RUNNING`, `PAUSED`, and `RINGING`.
- Completed session recording correctness.
- Square-screen layout on OPPO Watch 4 Pro.
- Round-screen clipping check when a version requires round-screen compatibility.
- Version-specific acceptance criteria from the active requirements document.
