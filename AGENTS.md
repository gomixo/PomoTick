# Agent Guide

## Goal

Build **PomoTick**, a lightweight tomato timer for **OPPO Watch 4 Pro** first.

Primary device:

- OPPO Watch 4 Pro
- ColorOS Watch V7.1
- Android 11 / API 30
- Square 1.91-inch screen

Optimize for: reliable timing, strong reminders, simple watch-first operation.

## Product Rules

- Do not build a phone-style productivity app.
- MVP screens only:
  - Timer
  - Quick actions
  - Reminder response
  - Settings
  - Today stats
- Defer weekly charts, Vico, mobile companion app, and round-screen polish.
- Core actions must be easy to tap on a watch: start, pause, resume, extend 5 min, finish early, abandon.

## Technical Rules

- Use Kotlin.
- Use a single app module for MVP.
- `minSdk = 30`.
- Do not depend on Google Play Services for core behavior.
- Set `android.hardware.type.watch` as `required=false`.
- Use Room for completed timer sessions.
- Use DataStore or a single runtime-state table for current timer state.
- Do not use Hilt, Dagger, or dependency-injection frameworks in MVP.
- Do not use complex Navigation frameworks in MVP. Prefer simple local screen state.
- Do not add charts, extra modules, or architecture layers unless explicitly requested.
- Use SDK 30+ compatible modern APIs. Do not generate deprecated legacy code.
- For vibration, use `VibrationEffect.createWaveform()` on API 30+.
- Prefer standard Compose layout primitives (`Column`, `Row`, `Box`, `LazyColumn`) for the OPPO Watch 4 Pro square screen.
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
- Never persist only “remaining seconds” as the recovery source.

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

- Pause stops focus time from accumulating.
- Resume shifts `targetEndAtEpochMillis` by paused duration.
- Extend adds 5 minutes to `targetEndAtEpochMillis`.
- Finish early records actual focus time.
- Abandon records interrupted state.

## Background And Reminder Rules

- Use `ForegroundService` for active timers.
- Do not use WorkManager for exact timer completion.
- On OPPO Watch 4 Pro, verify background behavior on real hardware.
- Guide user to set battery management to “unrestricted” if needed.
- Reminders must be strong but bounded:
  - focus done: strong vibration
  - break done: medium vibration
  - repeat every 30 seconds
  - max 10 repeats by default
  - stop immediately after user response

## Reference Projects

- `nsh07/Tomato` is reference only.
- Do not copy Tomato code by default because it is GPL-3.0.
- Borrow ideas, not implementation.

## Test Priorities

Always prioritize these checks:

- Timer accuracy after 25 minutes
- Pause/resume correctness
- Extend 5 minutes correctness
- App restart recovery
- Screen-off completion reminder
- Background completion reminder
- Vibration stop after response
- Square-screen layout on OPPO Watch 4 Pro
