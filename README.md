# PomoTick

PomoTick is a lightweight Pomodoro timer designed for watches and small-screen Android devices.

The app focuses on reliable timing, simple controls, lightweight settings, compact daily stats, and bounded reminders.

## Current Features

- Focus, short break, and long break timer phases.
- Timestamp-based timer state, instead of using a per-second loop as the source of truth.
- Start, pause, reset, phase switching, and reminder response actions.
- Settings for timer durations, round behavior, ringtone, and vibration strength.
- Today stats backed by completed timer sessions.
- Bounded sound, vibration, and notification reminders when a phase ends.
- Reminder actions stop sound and vibration immediately.

## Tech Stack

- Kotlin
- Jetpack Compose / Material 3
- Room for completed sessions
- DataStore Preferences for runtime state and settings
- ForegroundService for active timers
- AlarmManager for scheduled timer completion
- Android notifications for ongoing timer and reminder surfaces
- `VibrationEffect.createWaveform()` for API 30+ vibration

The app intentionally avoids Hilt, Dagger, complex Navigation frameworks, Google Play Services dependency for core behavior, chart libraries, and extra modules.

## Build Requirements

- JDK 17
- Android SDK installed
- Android Studio recommended

The project uses Gradle Wrapper, so a separate Gradle install is not required.

## Commands

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:installDebug --no-daemon
```

If the launcher keeps showing an old app icon, uninstall the debug package first:

```powershell
adb uninstall com.pomotick.debug
.\gradlew.bat :app:installDebug --no-daemon
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

```text
app/src/main/java/com/pomotick/
├── MainActivity.kt
├── PomoTickApp.kt
├── alarm/      AlarmManager scheduling and receiver
├── data/       Room, DataStore, repository
├── timer/      pure timer state machine
├── service/    foreground timer service and notifications
├── reminder/   vibration and reminder sound
└── ui/         ViewModel, screens, theme
```

## Notes

- `android.hardware.type.watch` is declared with `required="false"` for broader Android device compatibility.
- Local logs, research notes, generated plans, screenshots, and tool artifacts should not be committed. They are covered by `.gitignore`.
- Development constraints and product guardrails are documented in `AGENTS.md`.
