<div align="center">

# StatHD — WhatsApp Status HD Video Optimizer

**Hardware-Accelerated Android Video Optimizer to Bypass WhatsApp Status Compression**

[![Download APK](https://img.shields.io/badge/Download_APK-StatHD_v1.0.0-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/XC0D3-X/FramePure/releases/latest)
[![Telegram](https://img.shields.io/badge/Telegram-@harithwyd-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/harithwyd)
[![GitHub](https://img.shields.io/badge/GitHub-XC0D3--X-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/XC0D3-X)

</div>

---

## Overview
**StatHD** is a native Android application (built with **Kotlin** and **AndroidX Media3 Hardware Transformer**) engineered to preserve razor-sharp HD video quality on **WhatsApp Status**.

When uploading raw 1080p or 4K camera videos directly to WhatsApp Status, WhatsApp applies an ultra-fast, aggressive internal encoder that crushes bitrates and causes severe macroblocking and blur. **StatHD** pre-optimizes your video using hardware-accelerated `H.264 (AVC)` bitrate locking, OpenGL precision downscaling, micro-contrast sharpening, and automatic HDR-to-SDR tone-mapping so your video stays strictly below WhatsApp's destructive compression trigger.

---

## Key Features

### 1. Three Bitrate-Locked WhatsApp Status Presets
- **Pure 720p HD (Recommended • Sweet Spot):** Downscales short-side resolution to `720p` (`720x1280`) with a locked **`2.2 Mbps` H.264** video stream (`~5 MB` per 20s). Bypasses WhatsApp's aggressive re-compression while maximizing visual sharpness.
- **Ultra 1080p FHD (Max Clarity):** Targets `1080p` (`1080x1920`) with a locked **`3.2 Mbps` H.264** bitrate. Ideal for shorter 15–30s clips containing fine text and intricate details.
- **Long Status 60s (Size Saver `<12MB`):** Targets `720p` (`720x1280`) at **`1.5 Mbps` H.264** so full 60-second status videos remain under `~11 MB` without pixelation.

### 2. PureStatus Visual & Quota Engine
- **Crisp Micro-Contrast Boost:** Applies a subtle OpenGL shader contrast enhancement (`+8%`) during scaling to keep edges and typography crisp on mobile OLED screens.
- **Auto HDR-to-SDR Tone-Mapping (`Rec.709`):** Automatically tone-maps 10-bit HDR / HLG / Dolby Vision videos to standard 8-bit SDR so colors never appear washed out or overexposed on WhatsApp Status.
- **Remove Audio Mode (100% Video Bitrate Quota):** Optionally strips the audio track so 100% of WhatsApp's file-size cap is dedicated exclusively to video pixels.

### 3. Seamless Android Workflow
- **Share Directly from Gallery:** Select any video in your phone's Gallery ➔ tap **Share** ➔ choose **StatHD**.
- **One-Tap WhatsApp Status Dispatch:** Send the optimized MP4 directly to WhatsApp (`com.whatsapp`) or save it to `Movies/StatHD` in your Gallery.

---

## Technical Specifications
| Component | Specification |
| :--- | :--- |
| **Platform** | Android 8.0+ (`minSdk 26`, `targetSdk 34`) |
| **Language** | 100% Kotlin |
| **Transcoding Engine** | `androidx.media3:media3-transformer:1.3.1` + `media3-effect` (Hardware `MediaCodec` + OpenGL ES) |
| **Output Video Codec** | `H.264 / AVC` (`video/avc`) |
| **Output Audio Codec** | `AAC-LC` (`audio/mp4a-latm`) or Stripped (Muted Mode) |

---

## Building From Source
StatHD is automatically compiled and packaged into a signed installable `.apk` via **GitHub Actions** ([build-stathd-apk.yml](file:///c:/Users/harit/Downloads/HD%20VID%20MEDSOS/.github/workflows/build-stathd-apk.yml)).

To build locally with Gradle 8.7 and JDK 17:
```bash
cd StatHD
gradle assembleRelease
```

---

## Author
Built by **Harith**
- **Telegram:** [@harithwyd](https://t.me/harithwyd)
- **GitHub:** [XC0D3-X](https://github.com/XC0D3-X)
