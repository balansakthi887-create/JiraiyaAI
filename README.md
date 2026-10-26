# JiraiyaAI (Maxter J) — Naruto Tamil Dubbed Voice & Portable System Overlay Edition

This updated package upgrades **[balansakthi887-create/JiraiyaAI](https://github.com/balansakthi887-create/JiraiyaAI)** with two major features:

1. **Naruto Tamil Dubbed Jiraiya Voice Engine (`ta-IN` Toad Sage Baritone)**:
   - Automatically selects the deepest male Tamil (`ta-IN`) voice on Android (`MainActivity.kt` & `FloatingJiraiyaService.kt`).
   - Tuned to **Pitch `0.68f`** and **Speech Rate `0.92f`** with an `AudioTrack` sub-bass chest resonance layer to match the bold, theatrical Naruto Tamil dubbed Jiraiya ("தவளை முனிவர் ஜிரையா") persona.
   - Includes a live Voice Customizer (Pitch, Speed, Bass Resonance, and Preset Modes: *Toad Sage Normal*, *Boisterous Laugh*, *Serious Sage Mode*, and *Storyteller*).

2. **Portable System-Wide Floating Character Overlay (`SYSTEM_ALERT_WINDOW`)**:
   - Powered by `FloatingJiraiyaService.kt` using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
   - Renders the animated Jiraiya character **outside the app** on top of WhatsApp, YouTube, Chrome, Instagram, or the Android Home Screen.
   - **Move Anywhere**: Smooth 360° touch dragging to any `(X, Y)` screen coordinate with optional magnetic edge-snapping.
   - **Customizable Size**: Two-finger pinch-to-resize directly on the floating character or slider customization from `70dp` (compact badge) to `300dp` (full Sage companion), plus adjustable opacity (`30%–100%`).

## Updated & Added Files
- `app/src/main/AndroidManifest.xml` — Added `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, and registered `FloatingJiraiyaService`.
- `app/src/main/java/Com/jiraiyaai/app/MainActivity.kt` — Upgraded `AndroidVoiceBridge` with Tamil male voice selection, sub-bass resonance, and JS methods to launch/resize/stop the floating overlay.
- `app/src/main/java/Com/jiraiyaai/app/FloatingJiraiyaService.kt` — Foreground overlay service with multi-touch drag, pinch-to-resize, and Tamil TTS.
- `app/src/main/assets/overlay_webview.html` — Lightweight transparent animated Jiraiya overlay view.
- `app/src/main/assets/index.html` — Full interactive JiraiyaAI control center, Tamil voice studio, and floating overlay simulator.
