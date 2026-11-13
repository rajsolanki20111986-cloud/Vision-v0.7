# Vision (Android, Kotlin)

## Build on GitHub (no PC needed)
1. Upload everything in this zip to the ROOT of a GitHub repo (settings.gradle.kts must be at the top level).
2. Push to `main`, or Actions tab > "Build APK" > Run workflow.
3. When the run is green: open the run > Artifacts > `Vision-debug-apk`, download, unzip, install `app-debug.apk`.

## First run on the phone
1. Grant the permission prompts (microphone, contacts, location, photos, notifications).
2. Settings (gear, top right): paste the Gemini API key.
3. Settings > "Open Accessibility settings" > Vision Control > on.
   (Android 13+, sideloaded app: App info > three dots > "Allow restricted settings" first.)
4. Settings > "Allow launching apps" > allow Display over other apps.
5. Settings > "Notification access" > Vision Notifications > on, if you want message alerts.
6. Voice tab: tap the circle and talk. Chat tab: type or tap the mic.

## Memory (brain icon, top right)
Vision keeps its memory as plain files you can read, edit and delete:
- `profile.md` who you are (only what you told it)
- `facts/` important facts
- `routines/` habits and routines
- `projects/` project notes
- `skills/` things you taught it. Drop a file here describing a procedure and Vision follows it next time.

Voice and text share one history. Anything said aloud appears in the chat, and anything typed is remembered by voice.

## Where to edit
- `Persona.kt`: personality and the rules for memory, voice and actions.
- `LiveSession.kt`: `MODEL` name for the Live voice model.
- `ChatEngine.kt`: `MODEL` name for text chat.
- `ToolRouter.kt`: add tools (declaration + handler).
- `MemoryStore.kt`: memory folders and rules.
- `VisionAccessibilityService.kt`: `BLOCK` list of apps Vision must never touch.
