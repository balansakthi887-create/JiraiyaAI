# MAXTER J AI — Ultimate Voice Assistant & Real AI Upgrade (v4.0)

Target Device: **Infinix GT 20 Pro** (`Infinix X6871`, Android 15 / XOS 15.1.2, `compileSdk = 35`, `targetSdk = 35`)  
Package ID: `com.jiraiyaai.app`

## What Was Upgraded (Preserving Your Existing Character, UI & Features)

1. **Ultra-Fast Voice Command Pipeline (`MainActivity.kt` & `FloatingJiraiyaService.kt`)**
   - Removed the duplicate WebView `SpeechRecognition` wrapper that previously fired alongside Android's native `SpeechRecognizer`.
   - Tuned end-of-speech silence detection (`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 350L`, `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 280L`) so local commands execute in `< 5 ms` after recognition completes.
   - Local device commands (Flashlight, Brightness, Volume, App Launching, Navigation, Universal Search) never wait for an online LLM.

2. **Natural Wake-Word Detection + Optional Direct Commands**
   - Recognizes: `Yow`, `Yoww`, `Dei`, `Dai`, `Bro`, `Jiraiya`, `Master`, `Maxter`, `Ji` (plus Tamil script equivalents) and compound greetings (`"Dei, Jiraiya!"`, `"Yow Jiraiya"`).
   - Also understands high-confidence device commands even when you omit the wake word (`"Light on pannu"`, `"Instagram open pannu"`, `"Brightness increase pannu"`).
   - Includes a dedicated setting to toggle wake-word listening, a battery-saving mode (pauses when screen is off or battery `< 15%`), and an instant `⏹ Stop` button in the header.

3. **Structured Multilingual Intent Parser (`MaxterParsedIntent`)**
   - Supports **Tamil**, **English**, **Thanglish**, and mixed sentences.
   - Normalizes punctuation, transliterations, and word order (`"Dei, bright increase pannu"`, `"Brightness konjam kammi pannu"`, `"Media volume 50 percent pannu"`, `"YouTube-la Naruto video search pannu"`).
   - Prompts for clarification when confidence is low or when a target is ambiguous (`"Indha app open pannu"` → asks which app to open).

4. **Hardware & OS Controls**
   - **Flashlight**: Uses `CameraManager.setTorchMode` with `TorchCallback` state tracking and hardware/permission checks.
   - **Screen Brightness**: Recognizes both `"brightness"` and `"bright"`, gradual steps (`"konjam kammi"`), `minimum` / `maximum`, and verifies `Settings.System.canWrite` before reporting system-wide changes.
   - **Media Volume**: Uses `AudioManager.setStreamVolume` with `FLAG_SHOW_UI` (no unwanted beep sound), supporting `media` (default for `"sound"`), `ring`, `alarm`, and `call` streams.
   - **Universal App Opening**: Resolves spoken aliases (`"my music app"`, `"my file manager"`, `"insta"`) and searches all installed launcher activities (`<queries>` configured for Android 15).
   - **Legitimate App Closing & Navigation**: Includes optional `MaxterAccessibilityService` (`res/xml/accessibility_service_config.xml` with `canRetrieveWindowContent="false"`) for hands-free `Go back`, `Go to home`, and `Open recent apps`, plus standard Home intent exit with honest explanation of Android's force-stop security rules.

5. **Secure Real AI Backend (`netlify/functions/chat.js` & `cloudflare-worker-ai-proxy.js`)**
   - Stores your `GEMINI_API_KEY` (Google AI Studio Free Tier) and/or `GROQ_API_KEY` (Groq Free Tier) strictly in server-side environment variables — never in the APK or GitHub source.
   - Supports multi-turn conversation, Tamil/English tutoring, programming help, and allowlisted Android tool calling (`control_flashlight`, `set_brightness`, `set_volume`, `open_app`, `navigate_device`, `search_web`).

## How to Deploy Your Free Netlify AI Backend
1. Get a free Gemini API key from [Google AI Studio](https://aistudio.google.com/apikey) (no payment method required on the Free Tier) and/or a free Groq API key from [Groq Console](https://console.groq.com/keys).
2. Connect your GitHub repository to [Netlify](https://app.netlify.com/). Netlify automatically detects `netlify.toml` and deploys `netlify/functions/chat.js` at `https://<your-site>.netlify.app/.netlify/functions/chat`.
3. In Netlify **Site configuration → Environment variables**, add `GEMINI_API_KEY` (and optionally `GROQ_API_KEY`).
4. In Maxter J AI **Settings → D. AI & Chat**, select **Netlify Serverless Function** and enter `https://<your-site>.netlify.app/.netlify/functions/chat`.
