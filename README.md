# Screen Recorder

Tiny Android screen recorder. A "Screen record" tile in quick settings starts it; a notification
(timer + Stop button) shows while recording. Videos are saved to Movies/ScreenRecorder.

Audio (pick it in the app, or long-press the tile): No audio, Microphone, Internal audio,
or Microphone + internal audio.
- Internal audio needs Android 10+. Some apps block capture, so that part will be silent.
- Audio is AAC stereo (mic-only is played on both sides).
- If recording fails, the message on screen says why.

## Build the APK with GitHub Actions (nothing to install)
1. Unzip this project on a computer.
2. On github.com: New repository (any name, leave "Add a README" off) > Create repository.
3. Click "uploading an existing file" and drag in everything from INSIDE the unzipped folder,
   including the hidden .github folder (Mac: press Cmd+Shift+. in Finder to show it).
   Click Commit changes.
   Check that .github/workflows/build.yml is in the repo. If it is missing: Add file >
   Create new file, name it .github/workflows/build.yml, paste in that file from the zip, commit.
4. Open the Actions tab. "Build APK" starts by itself after the upload (or click Build APK >
   Run workflow). If it asks, click "I understand my workflows, go ahead and enable them".
5. After 3-5 minutes you get a green check. Open the run and download "ScreenRecorder-apk"
   under Artifacts.
6. Unzip it, copy ScreenRecorder.apk to your phone and tap it to install (allow "Install
   unknown apps"; if Play Protect warns, choose Install anyway).
If the run shows a red X, open the failed step and send the error text to Claude.

## Build with Android Studio instead
1. File > Open > pick this folder, wait for Gradle sync.
2. Build > Build Bundle(s) / APK(s) > Build APK(s).
3. The file is app/build/outputs/apk/debug/app-debug.apk

## Use it
1. Install the APK, open the app once: allow notifications, choose your audio, tap
   "Add tile to quick settings" (older Android: edit the quick panel and drag in "Screen record").
2. Pull down the panel, tap "Screen record", then "Start now".
3. Stop from the tile or the notification.
