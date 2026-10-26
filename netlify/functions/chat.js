// File Path in Repo: netlify/functions/chat.js
/**
 * Maxter J AI — Secure Netlify Serverless Function Backend (netlify/functions/chat.js)
 *
 * Security & Architecture:
 * - Stores GEMINI_API_KEY and/or GROQ_API_KEY strictly in server-side Netlify Environment Variables.
 * - Never exposes API keys in the Android APK, Kotlin source, HTML/JS, or client responses.
 * - Validates incoming requests, enforces payload size limits (<= 32 KB), and applies a 9s upstream timeout.
 * - Supports multi-turn conversation history, Tamil/English/Thanglish responses, and structured AI tool calling
 *   for allowlisted Android device actions (flashlight, brightness, volume, open_app, close_app, search_web).
 * - Automatically falls back from Primary Provider (Google Gemini Free Tier) to Fallback Provider (Groq Free Tier)
 *   if a rate limit (HTTP 429) or provider outage occurs.
 */

const MAX_BODY_BYTES = 32 * 1024; // 32 KB request size cap
const UPSTREAM_TIMEOUT_MS = 9000; // 9s timeout before returning clean fallback

// Simple in-memory per-IP rate limiter (protects free-tier quota from accidental loops)
const ipRateMap = new Map();
const WINDOW_MS = 60 * 1000;
const MAX_REQ_PER_MIN = 25;

function checkRateLimit(ip) {
  const now = Date.now();
  const entry = ipRateMap.get(ip) || { count: 0, resetAt: now + WINDOW_MS };
  if (now > entry.resetAt) {
    entry.count = 1;
    entry.resetAt = now + WINDOW_MS;
  } else {
    entry.count += 1;
  }
  ipRateMap.set(ip, entry);
  return entry.count <= MAX_REQ_PER_MIN;
}

const ALLOWED_TOOL_DECLARATIONS = [
  {
    name: "control_flashlight",
    description: "Turn the Android phone's hardware LED flashlight/torch ON or OFF.",
    parameters: {
      type: "OBJECT",
      properties: {
        state: { type: "STRING", enum: ["on", "off"], description: "Whether to turn the flashlight on or off" }
      },
      required: ["state"]
    }
  },
  {
    name: "set_brightness",
    description: "Adjust or set the Android phone's screen brightness.",
    parameters: {
      type: "OBJECT",
      properties: {
        action: { type: "STRING", enum: ["increase", "decrease", "max", "min", "set"], description: "Brightness adjustment type" },
        percent: { type: "INTEGER", description: "Target brightness percentage (0-100) if action is set" }
      },
      required: ["action"]
    }
  },
  {
    name: "set_volume",
    description: "Adjust or set the Android phone's media, ringtone, alarm, or call volume.",
    parameters: {
      type: "OBJECT",
      properties: {
        stream: { type: "STRING", enum: ["media", "ring", "alarm", "call"], description: "Audio stream (default media)" },
        action: { type: "STRING", enum: ["increase", "decrease", "max", "min", "mute", "set"], description: "Volume adjustment action" },
        percent: { type: "INTEGER", description: "Target volume percentage (0-100) if action is set" }
      },
      required: ["action"]
    }
  },
  {
    name: "open_app",
    description: "Launch an installed Android application by its spoken name.",
    parameters: {
      type: "OBJECT",
      properties: {
        app_name: { type: "STRING", description: "Name of the application to open (e.g. Instagram, YouTube, WhatsApp, Chrome, Settings)" }
      },
      required: ["app_name"]
    }
  },
  {
    name: "navigate_device",
    description: "Close an app or navigate to Back, Home screen, or Recent Apps.",
    parameters: {
      type: "OBJECT",
      properties: {
        navigation_action: { type: "STRING", enum: ["back", "home", "recents", "close_app"], description: "Navigation action" },
        target_app: { type: "STRING", description: "Optional app name if closing an app" }
      },
      required: ["navigation_action"]
    }
  },
  {
    name: "search_web",
    description: "Search for a query on Google, YouTube, Chrome, Play Store, Spotify, Maps, or Instagram.",
    parameters: {
      type: "OBJECT",
      properties: {
        provider: { type: "STRING", description: "Search destination (google, youtube, chrome, play store, spotify, maps, instagram)" },
        query: { type: "STRING", description: "Exact search terms to look up" }
      },
      required: ["provider", "query"]
    }
  }
];

exports.handler = async function (event) {
  const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Content-Type": "application/json; charset=utf-8"
  };

  if (event.httpMethod === "OPTIONS") {
    return { statusCode: 204, headers: corsHeaders, body: "" };
  }

  if (event.httpMethod !== "POST") {
    return {
      statusCode: 200,
      headers: corsHeaders,
      body: JSON.stringify({
        status: "online",
        service: "Maxter J AI Secure Netlify Function",
        providers_configured: {
          gemini: Boolean(process.env.GEMINI_API_KEY),
          groq: Boolean(process.env.GROQ_API_KEY)
        }
      })
    };
  }

  const clientIp =
    (event.headers && (event.headers["x-nf-client-connection-ip"] || event.headers["x-forwarded-for"])) ||
    "unknown";

  if (!checkRateLimit(clientIp)) {
    return {
      statusCode: 429,
      headers: corsHeaders,
      body: JSON.stringify({
        error: "Rate limit exceeded. Please wait a moment before sending another request."
      })
    };
  }

  const rawBody = event.body || "";
  if (Buffer.byteLength(rawBody, "utf8") > MAX_BODY_BYTES) {
    return {
      statusCode: 413,
      headers: corsHeaders,
      body: JSON.stringify({ error: "Request payload too large (max 32 KB)." })
    };
  }

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch (_) {
    return {
      statusCode: 400,
      headers: corsHeaders,
      body: JSON.stringify({ error: "Invalid JSON payload." })
    };
  }

  const userQuery = String(
    body.query ||
      body.message ||
      (Array.isArray(body.messages) && body.messages[body.messages.length - 1]?.content) ||
      ""
  ).trim();

  if (!userQuery) {
    return {
      statusCode: 400,
      headers: corsHeaders,
      body: JSON.stringify({ error: "Missing 'query' or 'message' field." })
    };
  }

  const language = String(body.language || "ta-IN");
  const preferredProvider = String(body.provider || process.env.DEFAULT_AI_PROVIDER || "gemini").toLowerCase();
  const history = Array.isArray(body.history) ? body.history.slice(-8) : [];

  const systemPrompt =
    (Array.isArray(body.messages) && body.messages[0]?.role === "system" && body.messages[0]?.content) ||
    `You are Maxter J, an intelligent, fast, friendly personal AI voice assistant running on an Infinix GT 20 Pro (Android 15).
You understand Tamil (தமிழ்), English, and Thanglish (Tamil spoken in English letters) fluently.
- When the user speaks in Tamil or Thanglish, reply warmly in natural Tamil or clear bilingual Tamil/English.
- When the user asks for explanations, tutoring, step-by-step English learning, programming help, or reasoning, give clear, structured, practical answers.
- Keep spoken answers concise (2 to 4 sentences) unless the user asks for a detailed step-by-step lesson or code snippet.
- If the user is asking to perform a supported phone action (flashlight, brightness, volume, opening an app, closing/navigating, or searching Google/YouTube), include the appropriate tool call.`;

  const geminiKey = process.env.GEMINI_API_KEY;
  const groqKey = process.env.GROQ_API_KEY;

  if (!geminiKey && !groqKey) {
    return {
      statusCode: 503,
      headers: corsHeaders,
      body: JSON.stringify({
        error: "No server API key configured. Add GEMINI_API_KEY or GROQ_API_KEY in Netlify Site Settings -> Environment Variables."
      })
    };
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);

  try {
    // Helper to call Google Gemini Free Tier
    async function callGemini() {
      const model = body.model && body.model.startsWith("gemini") ? body.model : "gemini-2.5-flash";
      const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(geminiKey)}`;

      const contents = [];
      for (const h of history) {
        if (h && h.text) {
          contents.push({
            role: h.sender === "user" ? "user" : "model",
            parts: [{ text: String(h.text).slice(0, 1200) }]
          });
        }
      }
      contents.push({
        role: "user",
        parts: [{ text: userQuery }]
      });

      const payload = {
        systemInstruction: { parts: [{ text: systemPrompt }] },
        contents,
        tools: [{ functionDeclarations: ALLOWED_TOOL_DECLARATIONS }],
        generationConfig: {
          temperature: 0.65,
          maxOutputTokens: 512
        }
      };

      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
        signal: controller.signal
      });

      if (!res.ok) {
        throw new Error(`Gemini HTTP ${res.status}`);
      }

      const data = await res.json();
      const parts = data?.candidates?.[0]?.content?.parts || [];
      let replyText = "";
      let toolCall = null;

      for (const p of parts) {
        if (p.text) replyText += p.text;
        if (p.functionCall) {
          toolCall = {
            name: p.functionCall.name,
            arguments: p.functionCall.args || {}
          };
        }
      }

      return {
        reply: replyText.trim() || (toolCall ? `Executing ${toolCall.name}...` : ""),
        tool_call: toolCall,
        provider: "gemini",
        model
      };
    }

    // Helper to call Groq Free Tier (OpenAI-compatible endpoint)
    async function callGroq() {
      const model = body.model && !body.model.startsWith("gemini") ? body.model : "llama-3.3-70b-versatile";
      const messages = [{ role: "system", content: systemPrompt }];
      for (const h of history) {
        if (h && h.text) {
          messages.push({
            role: h.sender === "user" ? "user" : "assistant",
            content: String(h.text).slice(0, 1200)
          });
        }
      }
      messages.push({ role: "user", content: userQuery });

      const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${groqKey}`
        },
        body: JSON.stringify({
          model,
          messages,
          temperature: 0.65,
          max_tokens: 512
        }),
        signal: controller.signal
      });

      if (!res.ok) {
        throw new Error(`Groq HTTP ${res.status}`);
      }

      const data = await res.json();
      const msg = data?.choices?.[0]?.message;
      return {
        reply: (msg?.content || "").trim(),
        tool_call: null,
        provider: "groq",
        model
      };
    }

    let result;
    if (preferredProvider === "groq" && groqKey) {
      try {
        result = await callGroq();
      } catch (e) {
        if (geminiKey) result = await callGemini();
        else throw e;
      }
    } else if (geminiKey) {
      try {
        result = await callGemini();
      } catch (e) {
        if (groqKey) result = await callGroq();
        else throw e;
      }
    } else {
      result = await callGroq();
    }

    clearTimeout(timeoutId);
    return {
      statusCode: 200,
      headers: corsHeaders,
      body: JSON.stringify(result)
    };
  } catch (err) {
    clearTimeout(timeoutId);
    return {
      statusCode: 502,
      headers: corsHeaders,
      body: JSON.stringify({
        error: "AI provider temporarily unavailable. Falling back to local assistant.",
        details: err.name === "AbortError" ? "Upstream timeout" : String(err.message || err)
      })
    };
  }
};
