/**
 * Maxter J AI — Optional Free Cloudflare Worker AI Proxy (cloudflare-worker-ai-proxy.js)
 *
 * Why use this?
 * - Keeps your free Google Gemini API key or Groq API key 100% on the server (never inside the Android APK).
 * - Free tier: 100,000 requests/day on Cloudflare Workers.
 * - Setup:
 *   1. Create a free worker at https://dash.cloudflare.com/
 *   2. Paste this script into worker.js
 *   3. In Worker Settings -> Variables and Secrets, add secret:
 *      - GEMINI_API_KEY (from https://aistudio.google.com/apikey) OR
 *      - GROQ_API_KEY   (from https://console.groq.com/keys)
 *   4. Paste your Worker URL (e.g. https://maxter-j-proxy.<your-subdomain>.workers.dev)
 *      into Maxter J -> Settings -> AI & Chat -> Endpoint URL.
 */
export default {
  async fetch(request, env) {
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    if (request.method !== "POST") {
      return new Response(JSON.stringify({ status: "ok", service: "Maxter J AI Proxy" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    try {
      const body = await request.json();
      const userQuery = body.query || (body.messages && body.messages[body.messages.length - 1]?.content) || "";
      const systemPrompt =
        (body.messages && body.messages[0]?.role === "system" && body.messages[0]?.content) ||
        "You are Maxter J, a fast, helpful Tamil, Thanglish, and English AI assistant for Android. Reply concisely in 1-3 sentences.";

      // Option 1: Use Groq Free Tier (llama-3.3-70b-versatile) if GROQ_API_KEY is configured
      if (env.GROQ_API_KEY) {
        const groqRes = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${env.GROQ_API_KEY}`,
          },
          body: JSON.stringify({
            model: body.model || "llama-3.3-70b-versatile",
            messages: [
              { role: "system", content: systemPrompt },
              { role: "user", content: userQuery },
            ],
            temperature: 0.6,
            max_tokens: 280,
          }),
        });
        const data = await groqRes.json();
        const reply = data?.choices?.[0]?.message?.content || "";
        return new Response(JSON.stringify({ reply }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      // Option 2: Use Google Gemini Free Tier (gemini-2.5-flash) if GEMINI_API_KEY is configured
      if (env.GEMINI_API_KEY) {
        const model = body.model || "gemini-2.5-flash";
        const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`;
        const geminiRes = await fetch(geminiUrl, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{ parts: [{ text: `${systemPrompt}\n\nUser: ${userQuery}` }] }],
          }),
        });
        const data = await geminiRes.json();
        const reply = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
        return new Response(JSON.stringify({ reply }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }

      return new Response(
        JSON.stringify({ error: "Add GEMINI_API_KEY or GROQ_API_KEY in Cloudflare Worker Secrets." }),
        { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } }
      );
    } catch (err) {
      return new Response(JSON.stringify({ error: String(err) }), {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }
  },
};
