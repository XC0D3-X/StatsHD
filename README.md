<div align="center">

# StatHD — WhatsApp Status True 1080p HD Optimizer

**Native Android Video & Photo Optimizer + Zero-Storage WhatsApp Cloud API Bot to Bypass WhatsApp Status Compression**

[![Download APK](https://img.shields.io/badge/Download_APK-Latest_Release-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/XC0D3-X/StatsHD/releases)
[![Self-Host Guide](https://img.shields.io/badge/Build_Your_Own-Step--by--Step_Guide-2563EB?style=for-the-badge&logo=github&logoColor=white)](./SELF_HOST_GUIDE.md)
[![Telegram](https://img.shields.io/badge/Telegram-@harithwyd-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/harithwyd)
[![GitHub](https://img.shields.io/badge/GitHub-XC0D3--X-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/XC0D3-X)

</div>

---

## Overview
**StatHD** is an open-source, ad-free native Android application (built with **Kotlin**, **AndroidX Media3**, and a **Zero-Storage Cloudflare Worker**) engineered to post razor-sharp **True 1080p HD** videos and photos to **WhatsApp Status** without watermarks and without WhatsApp's destructive `3.9 MB` / `720p` local re-compression.

> 📖 **Want to fork this project and build your own personal version with your own free WhatsApp Bot?**  
> 👉 **[Read the Complete Step-by-Step Self-Hosting Guide (`SELF_HOST_GUIDE.md`)](./SELF_HOST_GUIDE.md)**

---

## Key Features
1. **True 1080p Native Preservation + H.264 High Profile (CABAC):**
   - Preserves **1:1 native pixels up to `2200px`** (zero bilinear downscale blur on phone screen recordings like `1220x2168`).
   - Forces **H.264 High Profile (`AVCProfileHigh` / `Level 4.2`)** with 1-second keyframes and safe `< 16 MB` bitrate budgeting.
   - **100% Lossless Bit-for-Bit Stream Copy (`MediaExtractor` + `MediaMuxer`)** when your source clip already fits within size limits.
2. **Free WhatsApp Cloud API Bot Integration (`stathd-bot/`):**
   - Streams your optimized `1080p` MP4 (up to `16 MB`) directly through a zero-storage **Cloudflare Worker** to **Meta's Official WhatsApp Cloud API**, completely bypassing WhatsApp Android's local `720p` / `3.9 MB` status compressor.
   - Forward the untouched `1080p` video from your bot chat straight to **My Status**!
3. **Clean Zero-Scroll Native UI:**
   - Single-screen video editor with crisp Android Vector icons (`res/drawable/ic_*.xml`), Play/Pause timeline scrubber, clip trim controller, Hamburger settings sheet, and bottom-right `+` FAB.
   - **Live Before / After & Draggable 50/50 Split-Screen Comparison** (`Original | Split | Enhanced`) for both Videos and Photos.
4. **HD Photo Optimizer (`HdPhotoOptimizer.kt`):**
   - Unified `+` picker & Gallery Share sheet supports **both Videos and Photos** with EXIF orientation auto-fix, smart `1600px / 2048px` scaling, and JPEG Q92 export.
5. **Optional Wink GPU Shader & Gamer 90° Mode:**
   - Optional resolution-adaptive Dual-Radius (`3x3` + `7x7`) OpenGL ES 2.0 sharpening shader (`WinkUltraHdEffect.kt`) and **Rotate 90° Full-Screen Landscape** toggle.

---

## Repository Structure
- `app/` — Native Android Kotlin application (Media3 Transformer, OpenGL Shader, HD Photo Optimizer, Bot Uploader)
- `stathd-bot/` — Zero-storage Cloudflare Worker bridge for Meta WhatsApp Cloud API
- `.github/workflows/build-apk.yml` — Automated GitHub Actions workflow that compiles and publishes your signed `.apk`
- `SELF_HOST_GUIDE.md` — Complete beginner-friendly guide to fork, configure your own free bot, and build your own APK

---

## Author
Built by **Harith**
- **Telegram:** [@harithwyd](https://t.me/harithwyd)
- **GitHub:** [XC0D3-X](https://github.com/XC0D3-X)
