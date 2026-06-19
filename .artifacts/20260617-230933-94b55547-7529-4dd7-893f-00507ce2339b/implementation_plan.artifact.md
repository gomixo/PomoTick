# UI Optimization Plan (Phased)

This plan outlines the three-phase approach to improving PomoTick's UI for the OPPO Watch 4 Pro, focusing on visual hierarchy, density, and usability.

## Proposed Changes

### Phase 1: Theme, Timer & Reminders
Focus on the core visual identity and the most critical interactions.

#### [Theme.kt](file:///D:/Workspace/PomoTick/app/src/main/java/com/pomotick/ui/theme/Theme.kt)
- **Refine Dark Theme**: Adjust surface colors for better depth.
- **Phase Colors**: Define specific colors for different timer phases (Focus: Red, Break: Green/Teal).

#### [TimerScreen.kt](file:///D:/Workspace/PomoTick/app/src/main/java/com/pomotick/ui/screens/TimerScreen.kt)
- **Thicker Progress Ring**: Increase stroke width to ~20dp for a bolder, more premium look.
- **Phase-specific UI**: Update ring and text colors dynamically based on the active phase.
- **Icon Polish**: Refine the size and tint of the play/pause icon.

#### [ReminderScreen.kt](file:///D:/Workspace/PomoTick/app/src/main/java/com/pomotick/ui/screens/ReminderScreen.kt)
- **Button Hierarchy**: Differentiate "Got it" (Primary) and "Stop Ringing Only" (Secondary) more clearly through visual weight and color.

---

### Phase 2: Settings Density
Improve information density while maintaining watch-friendly touch targets.

#### [SettingsScreen.kt](file:///D:/Workspace/PomoTick/app/src/main/java/com/pomotick/ui/screens/SettingsScreen.kt)
- **Compact Layout**: Move to a "Label on left, Value/Control on right" or more vertically efficient grouping.
- **Touch Targets**: Ensure all clickable elements (buttons, switches) maintain at least a **48dp** touch area.
- **Refined Adjusters**: Replace full-width buttons in rows with more compact, yet easily tappable +/- controls.

---

### Phase 3: Stats Refinement
Subtle visual improvements to the statistics screen without over-designing.

#### [TodayStatsScreen.kt](file:///D:/Workspace/PomoTick/app/src/main/java/com/pomotick/ui/screens/TodayStatsScreen.kt)
- **Lightweight Grouping**: Use subtle backgrounds or separators for the summary section (Focus vs. Break time) instead of heavy cards.
- **Bar Chart Polish**: Add slight corner rounding to the `MiniBar` and improve color contrast for better readability.
- **Typography Consistency**: Ensure font sizes are legible at a glance.

---

## Verification Plan

### Automated Verification
- **UI State Checks**: Use `ui_state` to verify element bounds and ensure touch targets remain >= 48dp (roughly 48px * density) on the device.

### Manual Verification
- **Screen-by-Screen Walkthrough**:
    - **Timer**: Verify color transition when switching from Focus to Break phase. Confirm the thicker ring looks balanced on the square screen.
    - **Settings**: Test toggles and adjustments with one finger to ensure no "mis-taps" occur due to density.
    - **Stats**: Check readability of small labels and verify the bar charts accurately represent simulated data.
    - **Reminder**: Confirm the primary action is visually dominant and easily reachable.
- **Device Deployment**: Run on the real OPPO Watch 4 Pro to verify contrast and brightness of new colors under different lighting conditions.
