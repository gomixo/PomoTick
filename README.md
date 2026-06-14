# PomoTick

PomoTick is a lightweight tomato timer built first for **OPPO Watch 4 Pro**.

Target device:

- OPPO Watch 4 Pro
- ColorOS Watch V7.1
- Android 11 / API 30
- Square 1.91-inch screen

The MVP focuses on reliable timing, clear watch-first controls, and strong bounded reminders.

## Current MVP Flow

- Launch shows the selected phase duration: focus `25:00` or short break `05:00`.
- The main screen has three actions: start/pause, reset, and switch phase.
- Timer truth comes from timestamps, not from a per-second loop.
- When a phase ends, the app rings and vibrates, then shows a reminder screen.
- Tapping `停止响铃` stops sound/vibration/notification, records the completed session, prepares the next phase, and returns to the idle timer screen.

## Tech Stack

- Kotlin
- Jetpack Compose / Material 3
- Room for completed sessions
- DataStore Preferences for runtime state and settings
- ForegroundService for active timers
- `VibrationEffect.createWaveform()` for API 30+ vibration

MVP intentionally avoids Hilt, Dagger, complex Navigation frameworks, Google Play Services dependency for core behavior, charts, and extra modules.

## Build Requirements

- JDK 17
- Android SDK installed
- Android Studio recommended
- Real-device testing recommended on OPPO Watch 4 Pro

The project uses Gradle Wrapper, so a separate Gradle install is not required.

## Commands

From the project root:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:installDebug --no-daemon
```

If the watch launcher keeps showing an old app icon, uninstall the debug package first:

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
├── data/       Room, DataStore, repository
├── timer/      pure timer state machine
├── service/    foreground timer service and notifications
├── reminder/   vibration and reminder sound
└── ui/         ViewModel, screens, theme
```

## Real Watch Checks

Prioritize these checks before treating a build as usable:

- First launch of the day shows `25:00`.
- Switching to break and reopening still shows `05:00` on the same day.
- Start, pause, reset, and switch phase are easy to tap.
- `25:00`, `05:00`, `04:09`, and `00:59` render fully on the watch.
- Screen-off/background completion triggers reminder.
- Sound and vibration stop immediately after tapping `停止响铃`.
- After stopping a focus reminder, the idle screen prepares break; after stopping a break reminder, it prepares focus.

## Notes

- `android.hardware.type.watch` is declared with `required="false"` for ColorOS Watch compatibility.
- The launcher icon is generated as an adaptive icon with a safe-zone tomato foreground and light tomato-red background for ColorOS Watch launcher masks.
- The SDK XML warning about version 3 vs 4 usually indicates Android Studio / command-line tools version mismatch and does not block successful builds.
