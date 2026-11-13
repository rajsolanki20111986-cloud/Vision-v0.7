# v0.6 - Fix, power button, live chat agent

## Fixed
- MemoryStore.kt: the comment had facts/*.md etc. Kotlin treats /* as a new nested comment, so the comment never closed and MemoryStore did not exist ("Unresolved reference: MemoryStore"). Now written as facts/<name>.md.

## New
- Power button: Vision now appears in Settings > Digital assistant app (VisionAssistService, VisionSessionService, VisionRecognitionService, res/xml/voice_interaction_service.xml, manifest entries). Choose Vision there to start it with the long-press. If Android refuses to start the microphone service from the button, the app opens instead.
- Live chat agent for ANY chat app (ScreenAgent.kt). Tool converse_start: "WhatsApp par Rahul se baat karo, pucho kal aayega ya nahi". Vision opens the app, finds the person, writes, checks that its message appeared, watches the screen every 2 seconds, answers new messages, and ends the talk by itself (task done, goodbye, pointless or too long, money/code/risk). A short summary is written into the chat as [Agent].
- It tells his messages from theirs by the list of texts it sent and by the side of the screen the text is on. It does not look for ticks or any app-specific mark. No app is hard-coded.
- No fixed 5-9 second wait for notification replies any more. reply_delay is a setting (default 0 seconds).
- Settings he can change by chat (agent_settings): reply_delay, watch_seconds (2), max_messages (30), max_minutes (30), idle_minutes (10), unknown_after, reply_on_name, max_per_hour, triggers.

## Not verified
- Nothing was compiled or run here. Send the Actions log if the build fails.
- The live chat needs the screen on and unlocked and the Vision Control accessibility service on.
- Agent mode (notification replies) still needs testing on a real phone.

# v0.5 - Agent mode (replies by itself)

## New
- Agent mode (Settings switch, off by default). Works in the background through the notification listener.
- Replies go out through the notification's own Reply button, so no chat app has to open.
- You tell Vision who it may answer, by talking or typing: "Rahul ko tum khud reply karo, wo mera dost hai". Tools: agent_contact_set / agent_contact_remove / agent_contacts / agent_settings / agent_log.
- Known person: Vision answers after min_messages unanswered messages (default 1; you can set another number per person).
- Unknown person: ignored, unless they write "hello Vision", "hello AI", "Jarvis" (the words are editable). Then Vision answers politely ("I don't know you, who are you?") and tells you in the chat. You can also set unknown_after (number of messages) yourself; default 0 = off.
- Vision waits 5-9 seconds after the last message so a burst of messages gets one reply.
- Safety (in code): never replies in group chats; never answers messages about money, banks or codes; never shares location; at most 20 replies per person per hour (editable) and 3x that overall; a reply that mentions money or codes is blocked. Every reply is written into the chat as [Agent].
- Vision says it is an AI if asked. It never says it is you.

## Build
- Added .github/workflows/build.yml for a NEW GitHub repo (Gradle 8.10.2, JDK 17, no gradlew needed). The APK is in Actions > the run > Artifacts > Vision-debug-apk. Not run here.

## Not verified
- No Kotlin compiler here. Brackets are balanced and every changed line was read, but the first build may show compile errors. Send me the log.
- Reading "which message is mine" from a notification differs between chat apps. Test with WhatsApp first.

## Not done yet
- If a chat is OPEN on screen the app posts no notification, so the agent does not see that message. This needs an accessibility screen watcher (next step).
- Phone calls: Android does not give apps the call audio, so talking on a call is not possible. Placing a call needs the CALL_PHONE permission (not added).

# Vision v0.3: changes and status

## Fixed bugs
- Contacts, location and photos were never requested at runtime, so those tools always failed. All needed permissions are now asked at launch.
- Chat history lived only in a ViewModel and was lost on restart. It is now saved in `conversation.jsonl`.
- Voice and text were separate. Both now write to one history, and the voice model sees recent text messages (and the reverse).
- Notification messages were never sent to the voice model, and the sender name was lost (the app name was stored in its place). Both fixed.
- Typing replaced the whole field. It now appends, and falls back to paste when an app refuses.
- Screen reading skipped buttons without text (send, mic, camera icons). It now lists them by resource id or class, and includes scrollable areas and inputs.
- Memory reading skips broken lines, so one bad line cannot break the chat.
- Storage permission on Android 13+ now uses READ_MEDIA_IMAGES.
- Screenshot support needs `canTakeScreenshot` in the accessibility config, which is now set.

## New
- Memory: files for profile, facts, routines, projects and skills, with an editor on the brain screen.
- Tools: memory_list/read/write/append, post_to_chat (puts code or long text in the chat), gmail_search, capture_screen, torch, battery, set_alarm, universal open_app (matches any launcher app by name or package).
- Voice screen: blue arc, mic on/off and speaker on/off switches.
- Chat: his messages are blue, Vision's are yellow. Voice messages are marked with 🎙. The speaker button reads Hindi or English depending on the text.
- Settings: switches for phone control, memory, message alerts and Gmail, plus shortcuts for the permissions.
- Text chat now supports tool calls, so the same tools work by typing.

## Not verified
- This environment has no Kotlin compiler or Android SDK. I checked bracket balance and read every changed line, but the first GitHub build may still show compile errors. If it does, send me the log.
- The Live model name `gemini-3.1-flash-live-preview` and the screenshot field `realtimeInput.video` come from memory of the API. Check them against the current Gemini docs if voice or screenshots fail.
- Gmail uses a pasted access token, which expires (about an hour for playground tokens). A full OAuth login is still to do.

## Not done yet (honest list)
- 100% accurate tapping cannot be guaranteed. Tools fail on some apps, and Vision should re-check with read_screen or capture_screen after each step.
- Continuous screen watching every N seconds is not built. The model can call read_screen repeatedly, but there is no timer loop yet.
- Sending WhatsApp or other messages is not built. Vision can only read notifications and draft replies for you to copy.
- Wallpaper change and Spotify-specific control are not built. Music control uses media keys, which work with the screen off.
- Lock-screen commands work only for tools that don't need accessibility (torch, volume, media, alarm, battery, Gmail).
- Audio thread shutdown (AudioStreamer) can still race with release. It has not caused a crash in the code I read, but it is worth watching.
- The "500–600 lines" bug you mentioned is not in this zip. This version has no separate chat-memory file; the new history file is the one to check.
