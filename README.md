# Gemma Secure Chat

Android text chat app. Gemma 4 E4B (`litert-community/gemma-4-E4B-it-litert-lm`) runs inside the app with LiteRT-LM.

## APKs (`dist/`)
| File | Internet | How the model gets onto the phone |
|---|---|---|
| `GemmaSecureChat-offline-1.5.0.apk` | **None** (no permission at all) | Import `gemma-4-E4B-it.litertlm` from phone storage |
| `GemmaSecureChat-online-1.5.0.apk`  | Used once, for the model download | Downloads it in the app, or you import it |

In both builds the model file must match the pinned size and SHA-256
(`0b2a8980…52e0`, HF commit `2eee7ac3`). If it doesn't, the app rejects it.

## Install the offline build
```bash
adb install dist/GemmaSecureChat-offline-1.5.0.apk
# Download https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm
adb push gemma-4-E4B-it.litertlm /sdcard/Download/
```
Open the app, tap **Import model file**, and pick the file. The app copies it into private storage
and checks it. After that you can delete the copy in Download.

Requirements: Android 11 or newer, arm64, about 8 GB RAM recommended, about 4 GB free storage
(about 7.5 GB while importing).

## Voice chat (v1.1)
- **Mic button**: speak your question. The phone's **on-device** speech recognizer turns it into text and sends it
  (Android 12+). Cloud recognition is never used; if on-device recognition is unavailable, the app says so.
- **Voice mode** (speaker icon, top bar): replies are read aloud sentence by sentence using an offline TTS voice.
  After each reply the app listens again automatically, for a hands-free conversation.
- The mic and speaker stop as soon as the app leaves the screen. No audio is recorded or saved.
- If your language's offline speech pack is missing, Android 13+ downloads it automatically.
  On older versions: Settings → Google → Voice → Offline speech recognition.

## Meeting & call notes (v1.2)
- **Live meeting**: records the mic in a foreground service (runs with the screen off or in other apps, with a
  "Recording meeting" notification and a Stop button). Every 20 s clip is transcribed by Gemma's on-device audio
  encoder. Notes (summary, key points, decisions, action items) update about every minute.
- **Call recording**: Android doesn't let apps hear calls live. Turn on your phone's call recorder, then after the
  call share the recording to the app (or pick it in the app). It's decoded into 30 s clips, transcribed and
  summarized in the background.
- **Ask questions about this**: opens a chat that has the notes and transcript as context.
- Audio clips live only in the app's private cache and are deleted right after transcription. Transcripts and notes are
  kept in memory only. The notification never shows transcript text.
- If a phone can't load Gemma's audio encoder, meetings fall back to on-device speech recognition, which works only
  while the app is on screen.

## Scam Shield (v1.3): real-time OTP-fraud and "digital arrest" protection
- **OTP-during-call alarm** (no SMS permission): when your SMS app shows an OTP notification during a phone or WhatsApp call
  (or within 2 minutes of one), a full-screen warning appears over the call and the lock screen. It uses notification access.
  On Android 15+, where Android hides OTP text from such apps, a hidden-content SMS notification during a call triggers the
  warning. The OTP itself is never shown or stored.
- **Risky-call alert**: uses notification access and looks only at call notifications. It never requests or reads *Conversations*: that type is hidden in Android's "Allowed notification types", and conversation notifications are ignored in code. As a result, the OTP alarm only works where the SMS app posts OTPs as plain alerts rather than conversations. It warns about foreign (non +91)
  callers, video calls from unknown numbers, and calls with unknown numbers that last more than 10 minutes. Works with phone, WhatsApp, Telegram and Signal.
- **Automatic call protection** (Caller ID & spam role): Android starts the app on its own whenever a call from an
  unknown number begins, even when the app is closed. It warns about foreign numbers, shows a "Call protection active"
  notification with a Scam Check button, and after 10 minutes on the call shows the digital-arrest warning. It never blocks calls or hears audio.
- **After-call check-up**: when a call with an unknown number (phone or WhatsApp) ends after 30 s or more, a notification
  offers **Scam Check**, and for phone calls **Check recording**.
- **Scam check a recording**: the call-recording / meeting results screen has a **Scam check** button that gives a fraud verdict for the transcript.
- **Safety checklist** in Scam Shield: WhatsApp "Silence unknown callers", turning on phone call recording, Chakshu,
  cybercrime.gov.in, and a Call 1930 button.
- **Scam Check**: describe the call or message by typing or speaking. Gemma answers with a risk level (HIGH, MEDIUM or LOW), the red flags it found, and what to do now
  (call 1930, report at cybercrime.gov.in, report the number on Sanchar Saathi → Chakshu).
- Set it up with the shield icon in the top bar. For apps installed from an APK on Android 13+: App info → ⋮ → Allow restricted settings.

## Speed (v1.3)
- Two models: **E2B, Fast** (2.6 GB, about 2× faster) and **E4B, Smart** (3.7 GB). Switch with ⋮ → Switch model.
- The model stays loaded while the app is in memory, so reopening the app is instant.
- Hidden "thinking" is off and speculative decoding is on. The engine tries the GPU first, and replies are capped at 1024 tokens.

## Security measures
- All inference runs on the device. The offline build has no INTERNET permission. The online build uses HTTPS only, trusts only system CAs, and rejects cleartext traffic.
- The model is pinned by SHA-256 and stored in app-private `noBackupFilesDir`.
- A biometric or device-credential lock is required at launch and whenever the app returns from the background.
- `FLAG_SECURE` blocks screenshots, screen recording, and the Recents thumbnail.
- Chats are kept in memory only. They are never written to disk.
- Backup and device transfer are disabled.
- The keyboard is asked not to learn from typed text (`IME_FLAG_NO_PERSONALIZED_LEARNING`), and suggestions are off.
- Copied replies are marked sensitive on the clipboard.
- Release builds use R8 obfuscation, strip all `Log` calls, and exit if they are debuggable or a debugger is attached.
- APKs are signed with APK Signature Scheme v3 and an RSA-4096 key.

## Build
```bash
./gradlew assembleOfflineRelease assembleOnlineRelease
```
Signing uses `keystore.properties` and `keystore/release.jks`. Neither is committed. **Back them up:**
without them you cannot ship updates that install over the current app.
# LLMMobile
