# Self-Hosting & Forking Guide — Build Your Own StatHD (100% Free)

Want to build your own personal version of **StatHD** with **your own free WhatsApp Cloud API Bot**, **your own Cloudflare Worker**, and **automated GitHub Actions APK builds** — without installing Android Studio and at **$0 cost**?

Follow these 5 simple steps!

---

## Prerequisites (All 100% Free)
- A **GitHub** account (to fork this repo and compile your `.apk` automatically in the cloud)
- A **Meta for Developers** account (`developers.facebook.com`) for the free WhatsApp Cloud API
- A **Cloudflare** account (`dash.cloudflare.com`) for the free Cloudflare Worker bridge
- **Node.js 18+** installed on your PC (only needed for the 2-minute Cloudflare Worker deploy)

---

## Step 1: Fork This Repository on GitHub
1. Open **[https://github.com/XC0D3-X/StatsHD](https://github.com/XC0D3-X/StatsHD)**.
2. Click the **Fork** button at the top right to create your own copy (e.g. `https://github.com/YOUR_USERNAME/StatsHD`).
3. Enable GitHub Actions write permissions (so GitHub can automatically publish your compiled `.apk` to **Releases**):
   - Go to your forked repo's **Settings ➔ Actions ➔ General**.
   - Scroll down to **Workflow permissions**.
   - Select **Read and write permissions** and click **Save**.

---

## Step 2: Set Up Your Free Meta WhatsApp Cloud API Bot
1. Go to the Meta App Dashboard: **[https://developers.facebook.com/apps/](https://developers.facebook.com/apps/)**
2. Click **Create App** ➔ select **Other** ➔ **Next** ➔ select **Business** ➔ **Next**.
3. Give your app a name (e.g. `My StatHD Bot`) and click **Create App**.
4. Scroll down to find **WhatsApp** and click **Set up**.
5. In the left sidebar, go to **WhatsApp ➔ API Setup**.
6. Copy and save these **3 values**:
   - **Temporary Access Token** (starts with `EAA...`) ➔ this is your `WHATSAPP_TOKEN`
   - **Phone Number ID** (15-digit number under the "From" dropdown — *not* the phone number itself) ➔ this is your `WHATSAPP_PHONE_ID`
   - **Test Phone Number** (e.g. `+1 555 123 4567`) ➔ digits only (`15551234567`) is your `BUILT_IN_BOT_PHONE`
7. Under the **"To"** dropdown, click **Manage phone number list** and add your personal WhatsApp number (verify the OTP code sent to your WhatsApp).
8. Open WhatsApp on your phone and send `"Hi StatHD"` to the Meta **Test Phone Number**.  
   *(Important: Sending a message to your bot first opens Meta's **FREE 24-hour Service Window** so replies cost $0.00!)*

---

## Step 3: Deploy Your Own Free Cloudflare Worker (`stathd-bot`)
Clone your forked repository (or download the `stathd-bot/` folder) and run these commands in your terminal:

```bash
cd stathd-bot
npm install
npx wrangler login
npx wrangler secret put WHATSAPP_TOKEN
npx wrangler secret put WHATSAPP_PHONE_ID
npx wrangler deploy
```

> **Tip:** When Wrangler prompts `Enter a secret value:`, pasted characters are hidden for security — just paste (`Ctrl+V` or right-click) and press **Enter**.

Once `npx wrangler deploy` finishes, Cloudflare will print your live bot URL, for example:
```text
https://stathd-bot.your-subdomain.workers.dev
```

Verify it is online by opening that URL in your browser — you should see:
```json
{
  "status": "ok",
  "service": "StatHD WhatsApp Cloud API Bot",
  "configured": true,
  "maxVideoMb": 16
}
```

---

## Step 4: Connect the Android App to Your Own Bot
You don't even need Android Studio for this — you can edit this file directly on GitHub Web!

1. Open `app/src/main/java/com/stathd/purestatus/MainActivity.kt` in your forked repository.
2. Find the `companion object` near the top of `MainActivity.kt` (around line 65):
   ```kotlin
   companion object {
       private const val BUILT_IN_BOT_URL = "https://stathd-bot.your-subdomain.workers.dev"
       private const val BUILT_IN_BOT_PHONE = "15551234567"
   }
   ```
3. Replace:
   - `BUILT_IN_BOT_URL` with **your Cloudflare Worker URL** from Step 3.
   - `BUILT_IN_BOT_PHONE` with **your Meta Bot Phone Number** (digits only, no `+`, spaces, or dashes) from Step 2.
4. Commit your changes to the `main` branch!

---

## Step 5: Download Your Custom Compiled `.apk` from GitHub
1. As soon as you commit to `main`, go to the **Actions** tab of your forked repository.
2. Wait ~2 minutes for **Build StatHD Android APK** to turn green (`✓`).
3. Go to the **Releases** section of your repository (e.g. `https://github.com/YOUR_USERNAME/StatsHD/releases`).
4. Download your custom **`StatHD-*.apk`** directly onto your Android phone and install it!

### Using Your Personal StatHD App:
1. Open **StatHD** ➔ tap **☰ (Hamburger Menu)** ➔ **Register WhatsApp Number**.
2. Enter your WhatsApp number ➔ tap **Register & Open WhatsApp Bot** ➔ send `"Hi StatHD"` once.
3. Tap **+** to select a video or photo ➔ tap **Export HD Video** ➔ tap **Send via HD Bot (True 1080p)**.
4. Open the video in your WhatsApp bot chat ➔ tap **Forward ➔ My Status**!

---

## Optional: Upgrade to a Permanent Token & Public Number
Meta's free **Temporary Access Token** expires every 24 hours. To make your bot run forever without refreshing the token:
1. Go to **[Meta Business Settings](https://business.facebook.com/settings/system-users)** ➔ **System Users** ➔ create an **Admin System User**.
2. Assign your WhatsApp App to that System User with `whatsapp_business_messaging` and `whatsapp_business_management` permissions.
3. Click **Generate New Token** (select **Never expire**) and update your Cloudflare Worker secret:
   ```bash
   npx wrangler secret put WHATSAPP_TOKEN
   ```
4. To allow **anyone** (not just 5 test numbers) to use your bot, add any spare SIM card number (not currently registered on regular WhatsApp) in **WhatsApp ➔ API Setup ➔ Add phone number**.

---

## Troubleshooting
- **"File exceeds 16 MB limit":** Meta WhatsApp Cloud API enforces a strict 16 MB video cap. Trim your clip to 20–30 seconds or switch to the `720p HD` preset.
- **"Message failed / 24h window":** Send `"Hi StatHD"` to your bot in WhatsApp again to re-open the free 24-hour Meta Service Window.
- **GitHub Actions fails at `Publish APK to GitHub Releases`:** Make sure **Settings ➔ Actions ➔ General ➔ Workflow permissions** is set to **Read and write permissions**.
