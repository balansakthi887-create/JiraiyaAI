# Jiraiya AI — Updated Android Starter

## Requested configuration
- Android app
- Tamil + English interface
- Original anime-sage character inspired by the user's reference (not an exact copyrighted character)
- Full 3D animated character goal
- Natural Tamil/English voice goal
- Offline-first lessons and saved notes goal
- Floating assistant and screen-sharing are later Android-native milestones

## Honest status
This is a **starter project**, not a finished app or APK.
Included now:
- Android WebView app shell
- bilingual starter UI
- local demo chat replies (not a real AI model)
- small offline lesson examples stored in the app
- GitHub Actions workflow for a cloud debug build

Not implemented yet:
- real 3D character model, rig, facial animation, lip-sync, or gestures
- production AI backend / on-device LLM
- reliable Tamil + English offline speech recognition and TTS
- floating overlay service and permission flow
- screen capture / screen understanding
- cross-app control
- device testing or APK validation

## Build from a phone browser
1. Create a GitHub repository.
2. Upload these project files while preserving folders, including `.github/workflows/build-apk.yml`.
3. Open **Actions** and run **Build Jiraiya AI Android APK**.
4. Download the `JiraiyaAI-debug-APK` artifact after a successful run.
5. Extract it and install `app-debug.apk`.

If GitHub's mobile uploader cannot upload hidden `.github` folders, create `.github/workflows/build-apk.yml` manually in GitHub's **Add file → Create new file** interface.

## Planned implementation order
1. Build and install the shell; test screen sizes.
2. Add an optimized, licensed 3D model and animation rig (e.g. GLB/glTF), then test GPU/memory use.
3. Add offline speech models only after confirming Tamil is supported by the chosen engine and the model can be bundled/downloaded legally.
4. Add a local model or connect a secured AI backend. Never put API keys in app source code.
5. Implement floating bubble with Android overlay permission.
6. Implement screen capture only through Android's consent flow, with a visible stop control.
7. Add app-launch commands with explicit user action; Android restricts cross-app control.

## Compatibility
A universal guarantee for every Android phone is not realistic. 3D rendering and offline models vary by Android version, GPU, RAM, storage, and language-model availability.
