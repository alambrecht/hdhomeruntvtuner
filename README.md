# HDHR TV — Live TV Player for HDHomeRun

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Android TV](https://img.shields.io/badge/Android%20TV-3DDC84?logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Media3 ExoPlayer](https://img.shields.io/badge/Media3-ExoPlayer-FF6F00)
![Gradle](https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white)

HDHR TV is a minimal live-TV app for an HDHomeRun tuner, built for sideloading on an Nvidia Shield or Google TV. It has a channel list and a full-screen player, with no guide, DVR or account.

I built it because the official HDHomeRun app doesn't work across VLANs. My TV and my tuner are on separate network segments, and the official app finds tuners with local broadcast discovery, which doesn't cross subnets. So it never finds the tuner, even when the network routes traffic between the two. HDHR TV finds the tuner through SiliconDust's cloud discovery or a manually entered IP address, and it talks to the tuner only over plain HTTP, which routes normally between VLANs. The result is a quick, lightweight way to watch live TV from any segment of the network.

---

## Features

- **Automatic Tuner Discovery** – Finds the HDHomeRun through `api.hdhomerun.com/discover`. If that fails, you can enter the IP by hand.
- **ATSC 1.0 Lineup** – Loads channels from the tuner's `lineup.json` and filters out ATSC 3.0 and DRM channels.
- **Live Playback** – Plays MPEG-2 and H.264 broadcast streams through ExoPlayer, with small buffers for fast channel changes.
- **AC-3 Passthrough** – Sends Dolby Digital audio unchanged over HDMI to the TV or receiver.
- **Remote-Friendly Controls** – CH+/CH- and D-pad channel surfing, direct tuning with number keys, and an on-demand channel banner.
- **TV Launcher Ready** – Includes an Android TV banner and an adaptive app icon (with themed-icon support).
- **Release Build** – Shrinks the APK with R8 (about 1.9 MB versus 10.7 MB for debug) and signs it with your own key.

---

## Tech Stack

- Language: Kotlin 2.1.
- UI: Jetpack Compose with Compose for TV (`androidx.tv:tv-material`).
- Playback: AndroidX Media3 ExoPlayer 1.5.1 (progressive MPEG-TS over HTTP).
- Networking: `HttpURLConnection` + `org.json` (no third-party HTTP client).
- Build: Gradle 8.13 (wrapper), Android Gradle Plugin 8.13, JDK 17 or 21.
- Platform: compile/target SDK 35, min SDK 24 (Android 7.0).

---

## Quick Start

### Prerequisites

- Android Studio. It includes the Android SDK and downloads a JDK that works with Gradle.
- An HDHomeRun on the same network, reachable from the TV device on ports **80** (lineup) and **5004** (stream). If the TV is on another VLAN or subnet, allow that traffic in your router or firewall.
- An Nvidia Shield or Google TV device with developer options enabled, for sideloading.

### Installation

**Open the project**

Open this folder in Android Studio and let Gradle sync.

**Set up the command line (optional)**

`./gradlew` needs `JAVA_HOME` pointing to JDK 17 or 21. Android Studio's own bundled JBR is too new for Gradle 8.13, but the JDK that Studio downloads into `~/.jdks` works:
```bash
export JAVA_HOME=~/.jdks/jbr-21.0.11
```

**Development**
```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/hdhr-tv-1.0-debug.apk
```
In Android Studio, choose a device and press **Run**.

**Production Build**

Set up release signing first (see [Signing Configuration](#signing-configuration)), then:
```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/hdhr-tv-1.0-release.apk
```
In Android Studio, open **Build > Select Build Variant**, choose `release`, then run **Build > Build APK(s)**.

## Repository Structure
```bash
/HDHomerun Tuner for Android
├── /app
│   ├── /src/main
│   │   ├── /java/com/andyworks/hdhrtv
│   │   │   ├── HdhrClient.kt              # Tuner discovery and lineup.json parsing
│   │   │   ├── MainActivity.kt            # Channel list and tuner IP setup screen
│   │   │   └── PlayerScreen.kt            # ExoPlayer, channel switching, number entry
│   │   ├── /res
│   │   │   ├── /drawable                  # TV banner (320×180) and icon foreground/monochrome
│   │   │   ├── /mipmap, /mipmap-anydpi-v26  # App icon: pre-Android 8 fallback and adaptive
│   │   │   ├── /values                    # Icon background color
│   │   │   └── /xml/network_security_config.xml  # Allows plain HTTP (required by HDHomeRun)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                   # App config, dependencies, signing, R8
├── /gradle/wrapper                        # Gradle wrapper (8.13)
├── build.gradle.kts                       # Plugin versions
├── settings.gradle.kts                    # Repositories and modules
├── gradlew, gradlew.bat                   # Gradle wrapper scripts
├── keystore.properties                    # Release signing secrets (git-ignored, you create it)
├── hdhr-release.jks                       # Release key (git-ignored, you create it)
└── README.md
```

## Architecture Overview

**Channel List (`MainActivity.kt`)**
- Reads the saved tuner address, or discovers it on first launch, and saves it to SharedPreferences.
- Fetches the lineup and shows it as a focusable TV list. Opens the IP setup screen if discovery or loading fails.

**Player (`PlayerScreen.kt`)**
- Uses one ExoPlayer instance with a progressive MPEG-TS source. TS extractor flags let broadcast streams start without waiting for a keyframe.
- Waits 250 ms before tuning, so rapid CH+/CH- presses skip channels without opening each stream.
- Releases the player on exit, which closes the HTTP stream and frees the tuner.

### HDHomeRun Endpoints Used

| Endpoint | Method | Description |
|---|---|---|
| `https://api.hdhomerun.com/discover` | GET | Cloud discovery of tuners on your LAN |
| `http://<tuner>/lineup.json` | GET | Channel lineup |
| `http://<tuner>:5004/auto/v<channel>` | GET | Live MPEG-TS stream for a channel (uses one tuner) |

**Example `lineup.json` entry:**
```json
{
  "GuideNumber": "3.1",
  "GuideName": "KMTV-TV",
  "VideoCodec": "MPEG2",
  "AudioCodec": "AC3",
  "HD": 1,
  "URL": "http://192.168.68.10:5004/auto/v3.1"
}
```
The app skips entries with `"ATSC3": 1` or `"DRM": 1`.

## Signing Configuration

The release build reads its signing key from `keystore.properties` in the project root (the folder with `settings.gradle.kts`). If the file is missing, the release build falls back to the debug key.

**1. Create a keystore (once):**
```bash
~/.jdks/jbr-21.0.11/bin/keytool -genkeypair -v -keystore hdhr-release.jks -alias hdhr -keyalg RSA -keysize 2048 -validity 10000
```

**2. Create `keystore.properties`:**

| Property | Description |
|---|---|
| `storeFile` | Keystore path, relative to the project root (`hdhr-release.jks`) |
| `storePassword` | Keystore password |
| `keyAlias` | Key alias (`hdhr`) |
| `keyPassword` | Key password |

Both files are git-ignored. **Back up the `.jks` and its password.** Updates must be signed with the same key. Without it, you have to uninstall the app before installing a new build, which also clears the saved tuner address.

You don't need Android Studio's "Generate Signed APK" wizard. If you do use it, its **Key store path** is the `.jks` file, not `keystore.properties`.

## Deployment (Sideload)

| Step | On | Action |
|---|---|---|
| 1 | Shield | **Settings > Device Preferences > About** → click **Build** 7 times |
| 2 | Shield | **Developer options** → turn on **Network debugging** and note the IP (on Google TV: **USB debugging**) |
| 3 | PC | `adb connect <tv-ip>:5555`, then accept **Allow network debugging?** on the TV with **Always allow** ticked |
| 4 | PC | `adb install -r app/build/outputs/apk/release/hdhr-tv-1.0-release.apk` |

- If `adb devices` shows `unauthorized`, the prompt is still waiting on the TV.
- If the TV has a copy signed with a different key (for example a debug build), run `adb uninstall com.andyworks.hdhrtv` first.
- Once connected, the TV appears in Android Studio's device list, so **Run** works on it directly.
- To install without adb, copy the APK to a USB drive and open it with a file manager app (for example X-plore). You need to allow that app to **Install unknown apps**.

The app appears as **HDHR TV** in the app row.

## Usage

| Remote button | Action |
|---|---|
| D-pad up / CH+ | Next channel |
| D-pad down / CH- | Previous channel |
| Number keys | Direct tune after 2 s (`61` or `6` → 6.1) |
| OK | Show or hide the channel banner |
| Back | Return to the channel list |

To point the app at a different tuner, use **Tuner address** on the channel list.

### Audio

For AC-3 passthrough on the Shield, set **Settings > Device Preferences > Display & Sound > Advanced sound settings > Surround sound** to *Auto* or *Always* with Dolby Digital enabled. If the HDMI sink doesn't report AC-3 support, ExoPlayer uses a platform decoder if one exists. If it has neither, the audio is silent.

## Testing

There are no automated tests. Test on a real device or an emulator.

- **Nvidia Shield (recommended):** full video and AC-3 audio.
- **Android TV emulator:** Device Manager > **TV** system image. Android enables its MPEG-2 software decoder only on TV images, so video plays, but without sound, because the emulator has no AC-3 decoder.
- **Phone emulator:** not usable for playback. It has neither decoder, so channels end silently on a black screen.

The emulator needs KVM (`/dev/kvm`). If it's missing, check that `kvm_intel` or `kvm_amd` isn't blacklisted in `/etc/modprobe.d/`.

## Versioning

Uses Semantic Versioning (`MAJOR.MINOR.PATCH`) in `versionName`. For each release, also bump `versionCode` in `app/build.gradle.kts`. Android refuses to install a build whose `versionCode` is lower than the installed one, and the TV's app info shows `versionName`, so you can tell which build is installed.
