/**
 * StatHD WhatsApp Cloud API Bot — Cloudflare Worker (Zero-Storage Bridge)
 *
 * Endpoints:
 *   GET  /          -> Health check
 *   GET  /webhook   -> Meta WhatsApp Webhook verification
 *   POST /webhook   -> Auto-replies when user registers / opens 24h free window
 *   POST /send-hd   -> Streams 1080p MP4 (<= 16MB) or HD JPG directly to Meta Cloud API
 *                      and delivers it to the user's WhatsApp chat with ZERO server storage.
 */

const GRAPH_API_VERSION = "v21.0";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...CORS_HEADERS,
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    // 1. Health Check
    if (request.method === "GET" && url.pathname === "/") {
      return jsonResponse({
        status: "ok",
        service: "StatHD WhatsApp Cloud API Bot",
        configured: Boolean(env.WHATSAPP_TOKEN && env.WHATSAPP_PHONE_ID),
        maxVideoMb: 16,
      });
    }

    // 2. Meta Webhook Verification (GET /webhook)
    if (request.method === "GET" && url.pathname === "/webhook") {
      const mode = url.searchParams.get("hub.mode");
      const token = url.searchParams.get("hub.verify_token");
      const challenge = url.searchParams.get("hub.challenge");
      const expectedToken = env.WEBHOOK_VERIFY_TOKEN || "stathd_verify_2026";

      if (mode === "subscribe" && token === expectedToken) {
        return new Response(challenge, { status: 200 });
      }
      return new Response("Forbidden", { status: 403 });
    }

    // 3. Incoming WhatsApp Messages Webhook (POST /webhook)
    // Opens the FREE 24-hour Meta Service Conversation Window when the user says Hi!
    if (request.method === "POST" && url.pathname === "/webhook") {
      try {
        const body = await request.json();
        const messages =
          body?.entry?.[0]?.changes?.[0]?.value?.messages || [];

        for (const msg of messages) {
          const fromPhone = msg?.from;
          if (fromPhone && env.WHATSAPP_TOKEN && env.WHATSAPP_PHONE_ID) {
            await fetch(
              `https://graph.facebook.com/${GRAPH_API_VERSION}/${env.WHATSAPP_PHONE_ID}/messages`,
              {
                method: "POST",
                headers: {
                  Authorization: `Bearer ${env.WHATSAPP_TOKEN}`,
                  "Content-Type": "application/json",
                },
                body: JSON.stringify({
                  messaging_product: "whatsapp",
                  to: fromPhone,
                  type: "text",
                  text: {
                    body: "StatHD Connected! Your 24-hour Free True 1080p HD delivery window is active.\n\nGo back to the StatHD app, export your 1080p video, and tap 'Send via HD Bot'.",
                  },
                }),
              }
            );
          }
        }
      } catch (_) {
        // Always return 200 OK to Meta webhooks so retries don't pile up
      }
      return new Response("EVENT_RECEIVED", { status: 200 });
    }

    // 4. Direct True 1080p HD Delivery Endpoint (POST /send-hd)
    if (request.method === "POST" && url.pathname === "/send-hd") {
      if (!env.WHATSAPP_TOKEN || !env.WHATSAPP_PHONE_ID) {
        return jsonResponse(
          {
            error:
              "Bot secrets not configured. Set WHATSAPP_TOKEN and WHATSAPP_PHONE_ID via wrangler secret put.",
          },
          500
        );
      }

      try {
        const formData = await request.formData();
        const rawPhone = String(formData.get("phone") || "").replace(/[^0-9]/g, "");
        const file = formData.get("file");
        const mediaType = String(formData.get("mediaType") || "video"); // "video" or "image"

        if (!rawPhone || rawPhone.length < 8) {
          return jsonResponse(
            { error: "Invalid WhatsApp phone number. Use international format without + (e.g. 60123456789)." },
            400
          );
        }

        if (!file || typeof file === "string") {
          return jsonResponse({ error: "Missing video/image file upload." }, 400);
        }

        // Enforce Meta Cloud API 16 MB video limit
        if (file.size > 16 * 1024 * 1024) {
          return jsonResponse(
            { error: "File exceeds WhatsApp Cloud API 16 MB limit." },
            413
          );
        }

        // Step A: Stream media directly to Meta's WhatsApp Cloud API /media endpoint (Zero server storage!)
        const metaUploadForm = new FormData();
        metaUploadForm.append("messaging_product", "whatsapp");
        const contentType =
          mediaType === "image" ? "image/jpeg" : "video/mp4";
        const fileName =
          mediaType === "image" ? "stathd_hd.jpg" : "stathd_1080p.mp4";
        metaUploadForm.append(
          "file",
          new Blob([await file.arrayBuffer()], { type: contentType }),
          fileName
        );

        const uploadRes = await fetch(
          `https://graph.facebook.com/${GRAPH_API_VERSION}/${env.WHATSAPP_PHONE_ID}/media`,
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${env.WHATSAPP_TOKEN}`,
            },
            body: metaUploadForm,
          }
        );

        const uploadData = await uploadRes.json();
        if (!uploadRes.ok || !uploadData.id) {
          return jsonResponse(
            {
              error:
                uploadData?.error?.message ||
                "Meta Media Upload failed. Check WHATSAPP_TOKEN & WHATSAPP_PHONE_ID.",
              metaError: uploadData,
            },
            502
          );
        }

        // Step B: Deliver untouched 1080p HD media directly to user's WhatsApp chat
        const messagePayload =
          mediaType === "image"
            ? {
                messaging_product: "whatsapp",
                to: rawPhone,
                type: "image",
                image: {
                  id: uploadData.id,
                  caption: "StatHD True HD Photo — Tap Forward ➔ My Status",
                },
              }
            : {
                messaging_product: "whatsapp",
                to: rawPhone,
                type: "video",
                video: {
                  id: uploadData.id,
                  caption: "StatHD True 1080p HD — Tap Forward ➔ My Status",
                },
              };

        const msgRes = await fetch(
          `https://graph.facebook.com/${GRAPH_API_VERSION}/${env.WHATSAPP_PHONE_ID}/messages`,
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${env.WHATSAPP_TOKEN}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify(messagePayload),
          }
        );

        const msgData = await msgRes.json();
        if (!msgRes.ok) {
          return jsonResponse(
            {
              error:
                msgData?.error?.message ||
                "Failed to send WhatsApp message. Make sure you messaged the bot first to open the 24h window.",
              metaError: msgData,
            },
            502
          );
        }

        return jsonResponse({
          success: true,
          mediaId: uploadData.id,
          messageId: msgData?.messages?.[0]?.id || null,
        });
      } catch (err) {
        return jsonResponse(
          { error: err?.message || "Unexpected worker error" },
          500
        );
      }
    }

    return jsonResponse({ error: "Not found" }, 404);
  },
};
