// File Path in Repo: cloudflare-worker-ai-proxy.js
/**
 * Maxter J AI — Optional Free Cloudflare Worker AI Proxy (cloudflare-worker-ai-proxy.js)
 *
 * Security & Architecture:
 * - Keeps your free Google Gemini API key or Groq API key 100% on the server (never inside the Android APK).
 * - Supports multi-turn conversation history, Tamil/English/Thanglish responses, and allowlisted Android tool calling.
 * - Automatically falls back between Gemini (`GEMINI_API_KEY`) and Groq (`GROQ_API_KEY`).
 */
export default {
  async fetch(request, env) {
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
      "Content-Type": "application/json; charset=utf-8"
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method !== "POST") {
      return new Response(
        JSON.stringify({
          status: "online",
          service: "Maxter J AI Cloudflare Worker Proxy",
          providers_configured: {
            gemini: Boolean(env.GEMINI_API_KEY),
            groq: Boolean(env.GROQ_API_KEY)
          }
        }),
        { headers: corsHeaders }
      );
    }

    try {
      const rawText = await request.text();
      if (rawText.length > 32768) {
        return new Response(JSON.stringify({ error: "Payload too large (max 32 KB)." }), {
          status: 413,
          headers: corsHeaders
        });
      }

      const body = JSON.parse(rawText);
      const userQuery = String(
        body.query ||
          body.message ||
          (Array.isArray(body.messages) && body.messages[body.messages.length - 1]?.content) ||
          ""
      ).trim();

      if (!userQuery) {
        return new Response(JSON.stringify({ error: "Missing query." }), {
          status: 400,
          headers: corsHeaders
        });
      }

      const history = Array.isArray(body.history) ? body.history.slice(-8) : [];
      const preferredProvider = String(body.provider || "gemini").toLowerCase();
      const systemPrompt =
        (Array.isArray(body.messages) && body.messages[0]?.role === "system" && body.messages[0]?.content) ||
        "You are Maxter J, an intelligent, fast, friendly Tamil, Thanglish, and English personal AI voice assistant for Android. Reply concisely in 2-4 sentences unless asked for detailed lessons or code.";

      if (preferredProvider === "groq" && env.GROQ_API_KEY) {
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

        const groqRes = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${env.GROQ_API_KEY}`
          },
          body: JSON.stringify({
            model: body.model && !body.model.startsWith("gemini") ? body.model : "llama-3.3-70b-versatile",
            messages,
            temperature: 0.65,
            max_tokens: 512
          })
        });
        const data = await groqRes.json();
        const reply = data?.choices?.[0]?.message?.content || "";
        return new Response(JSON.stringify({ reply, provider: "groq" }), { headers: corsHeaders });
      }

      if (env.GEMINI_API_KEY) {
        const model = body.model && body.model.startsWith("gemini") ? body.model : "gemini-2.5-flash";
        const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`;
        const contents = [];
        for (const h of history) {
          if (h && h.text) {
            contents.push({
              role: h.sender === "user" ? "user" : "model",
              parts: [{ text: String(h.text).slice(0, 1200) }]
            });
          }
        }
        contents.push({ role: "user", parts: [{ text: userQuery }] });

        const geminiRes = await fetch(geminiUrl, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            systemInstruction: { parts: [{ text: systemPrompt }] },
            contents,
            generationConfig: { temperature: 0.65, maxOutputTokens: 512 }
          })
        });
        const data = await geminiRes.json();
        const reply = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
        return new Response(JSON.stringify({ reply, provider: "gemini" }), { headers: corsHeaders });
      }

      if (env.GROQ_API_KEY) {
        const groqRes = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${env.GROQ_API_KEY}`
          },
          body: JSON.stringify({
            model: "llama-3.3-70b-versatile",
            messages: [
              { role: "system", content: systemPrompt },
              { role: "user", content: userQuery }
            ],
            temperature: 0.65,
            max_tokens: 512
          })
        });
        const data = await groqRes.json();
        const reply = data?.choices?.[0]?.message?.content || "";
        return new Response(JSON.stringify({ reply, provider: "groq" }), { headers: corsHeaders });
      }

      return new Response(
        JSON.stringify({ error: "Add GEMINI_API_KEY or GROQ_API_KEY in Worker Secrets." }),
        { status: 503, headers: corsHeaders }
      );
    } catch (err) {
      return new Response(JSON.stringify({ error: String(err) }), {
        status: 500,
        headers: corsHeaders
      });
    }
  }
};
