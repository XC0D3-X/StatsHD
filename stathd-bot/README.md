# StatHD Bot — Free WhatsApp Cloud API Bridge (Cloudflare Worker)

This lightweight Cloudflare Worker streams your **True 1080p HD** MP4 (up to `16 MB`) directly from the **StatHD Android App** into **Meta's Official WhatsApp Cloud API**, delivering the untouched `1080p` video straight to your WhatsApp chat so you can tap **Forward ➔ My Status** with **ZERO `720p` / `3.9 MB` re-compression** and **ZERO server storage**.

---

## 3-Minute Free Setup Guide

### Step 1: Get Free Meta WhatsApp Cloud API Credentials (RM0)
1. Go to **[https://developers.facebook.com/](https://developers.facebook.com/)** and log in.
2. Click **My Apps ➔ Create App ➔ Other ➔ Business**.
3. Add the **WhatsApp** product and open **WhatsApp ➔ API Setup**.
4. Copy these 2 values:
   - **Temporary / Permanent Access Token** (`WHATSAPP_TOKEN`)
   - **Phone Number ID** (`WHATSAPP_PHONE_ID`)
5. Under **"To" (Recipient Phone Number)**, click **Manage phone number list** and add your own WhatsApp number (e.g. `60123456789`) — Meta sends a 5-digit OTP to verify it.
6. Send `"Hi StatHD"` from your phone to the Meta **Test Number** shown on that page (this opens the free 24-hour service window!).

---

### Step 2: Deploy This Cloudflare Worker (RM0 Free Tier)
Run these commands inside the `stathd-bot/` folder:

```bash
cd stathd-bot
npm install
npx wrangler login
npx wrangler secret put WHATSAPP_TOKEN
npx wrangler secret put WHATSAPP_PHONE_ID
npx wrangler deploy
```

After `npx wrangler deploy`, Cloudflare prints your live bot URL, for example:
`https://stathd-bot.<your-subdomain>.workers.dev`

---

### Step 3: Paste Your Bot URL in the StatHD Android App
1. Open **StatHD** on your Android phone ➔ tap **☰ (Hamburger Menu)** ➔ **WhatsApp HD Bot Setup**.
2. Enter:
   - **Bot Server URL:** `https://stathd-bot.<your-subdomain>.workers.dev`
   - **Your WhatsApp Number:** `60123456789` (international format without `+`)
   - **Bot WhatsApp Number:** *(optional — the Meta Test Number so you can open chat in 1 tap)*
3. Export any video using **1080p Full HD • Max 15MB** ➔ tap **Send via HD Bot (True 1080p)** ➔ Forward from WhatsApp chat to **My Status**!
