# FloWave — Hi-Res Music Streaming & Offline Engine

FloWave is an open-source, ultra-fidelity Android audio player built with Kotlin and Jetpack Compose. It features native InnerTube YouTube audio extraction, Seal downloader support, dynamic audio routing, 10-band parametric equalizer, synced LRC lyrics, customizable glassmorphism visuals, and local Room offline database persistence.

---

## 🚀 Building APK with GitHub Actions Workflow

This repository is pre-configured with a automated **GitHub Actions Workflow** (`.github/workflows/android-build.yml`) that automatically builds and packages downloadable APKs.

### How to trigger a build:
1. **Push to `main` or `master` branch**: Every commit automatically triggers a workflow run under the **Actions** tab in your GitHub repository.
2. **Push a Release Tag**: Push a tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`) to automatically generate a GitHub Release with attached `FloWave-Debug-APK.apk`.
3. **Manual Trigger (`workflow_dispatch`)**: Go to **Actions** -> **Build FloWave APK** -> **Run workflow**.

### Downloading the APK:
1. Navigate to the **Actions** tab of your repository.
2. Click on the latest workflow run.
3. Scroll down to the **Artifacts** section at the bottom of the page.
4. Download `FloWave-Debug-APK` or `FloWave-Release-APK`.

---

## 🛠️ Local Development & Build

### Requirements
- **JDK**: Java 17 or higher
- **Android SDK**: API 36 (Android 16) with build-tools 36.0.0
- **Gradle**: 9.3.1 (required by AGP 9.1.1)

### Commands
```bash
# Clone the repository
git clone https://github.com/your-username/flowave.multiplatform.git
cd flowave

# Build Debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## ✨ Features Implemented
- 🔍 **Keyword Audio Search & Full Streaming**: Search songs by title or artist (e.g. "Arz Kiya Hai") and stream full 320kbps / FLAC audio streams.
- ⚡ **Dynamic Island & Live Waveform**: System top pill visualizer for active track state, cover art, and real-time audio canvas equalizer.
- 🎧 **Dynamic System Audio Route Selector**: Roundish pill that dynamically queries Android `AudioManager` and `AudioDeviceInfo` to route output to Bluetooth, USB DAC, or Stereo Speakers.
- 🔥 **Daily Listening Streak Engine**: Real-time streak tracking persisted in DataStore and updated on daily playback.
- ⬇️ **Seal Downloader**: Keyword and direct-link audio extractor saved into local Room database and device media store.
- 🎨 **Custom Glassmorphism & Wallpapers**: Liquid Glass theme presets and custom background image URL rendering.
