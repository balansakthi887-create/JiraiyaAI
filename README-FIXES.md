# JiraiyaAI (Maxter J) — Fixed 2D Animated Character & Voice Package

## Root Causes Identified & Fixed
1. **Case-Sensitive Asset Path Mismatch (`Images/` vs `images/`)**:
   - Your repository folder is `app/src/main/assets/Images/21574.png` (capital `I`), while `index.html` requested `images/21574.png` / `images/maxter-j-main.png` (lowercase `i`). Android's `file:///android_asset/` filesystem is strictly case-sensitive, causing the character image and background to 404.
   - **Fix**: `index.html` now resolves `Images/21574.png`, `images/21574.png`, `Images/maxter-j-main.png`, `images/maxter-j-main.png`, and includes an embedded 2D character sprite fallback.

2. **2D Speaking Animation Cancelled Immediately on Speech Error**:
   - In `index.html`, `sendMessage()` called `setCharacterAction('speaking', 1800)` followed immediately by `speak(response)`. Inside `speak()`, `utterance.onerror = () => { setCharacterAction(null); }` fired immediately in Android WebView (where `window.speechSynthesis` is unsupported), cancelling `.action-speaking` within milliseconds.
   - **Fix**: Decoupled the 2D animation timer from `utterance.onerror`, added multi-layered 2D idle/greeting/thinking/speaking animations, chakra particle aura, and live lip-sync waveform bars.

3. **Missing Android Native TTS Bridge & Permissions (`MainActivity.kt` & `AndroidManifest.xml`)**:
   - Standard Android `WebView` does not implement `window.speechSynthesis` out of the box and blocked automatic audio playback.
   - **Fix**: Added `TextToSpeech` (`@JavascriptInterface` `AndroidVoice.speak(text, lang)`), `mediaPlaybackRequiresUserGesture = false`, `WebChromeClient` permission handling in `MainActivity.kt`, Web Audio API voice synth fallback in `index.html`, and `RECORD_AUDIO` + `<queries>` TTS service declaration in `AndroidManifest.xml`.
