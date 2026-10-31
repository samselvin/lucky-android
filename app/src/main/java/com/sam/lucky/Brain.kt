package com.sam.lucky

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Talks to Google Gemini (free plan) and lets it control the phone through tools. */
class Brain(private val ctx: Context) {

    private val models = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    private val history = ArrayList<JSONObject>()

    private fun key(): String =
        ctx.getSharedPreferences("lucky", Context.MODE_PRIVATE).getString("gemini_key", "")?.trim() ?: ""

    private fun system(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return """You are Lucky, Sam's personal voice assistant on his Android phone.
Personality: professional, polite, calm and efficient.
Your replies are SPOKEN aloud: answer in 1-2 short sentences, plain text only, no markdown, no lists, no emojis.
Current local date and time: $now.
Use the set_alarm tool for alarms or wake-up requests (hour is 0-23; work out times like "in 20 minutes" from the current time).
Use the set_timer tool for timers and countdowns.
You cannot browse the internet; for very recent news or prices, say your information may be out of date.
Always reply in English."""
    }

    private fun tools(): JSONArray {
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        val alarm = JSONObject()
            .put("name", "set_alarm")
            .put("description", "Set an alarm in the phone's Clock app.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("hour", prop("integer", "Hour 0-23"))
                    .put("minute", prop("integer", "Minute 0-59"))
                    .put("label", prop("string", "Optional alarm label")))
                .put("required", JSONArray().put("hour").put("minute")))
        val timer = JSONObject()
            .put("name", "set_timer")
            .put("description", "Start a countdown timer in the phone's Clock app.")
            .put("parameters", JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("seconds", prop("integer", "Timer length in seconds"))
                    .put("label", prop("string", "Optional label")))
                .put("required", JSONArray().put("seconds")))
        return JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(alarm).put(timer)))
    }

    private fun runTool(name: String, args: JSONObject): String = when (name) {
        "set_alarm" -> Commands.setAlarm(ctx, args.optInt("hour").coerceIn(0, 23), args.optInt("minute").coerceIn(0, 59),
            args.optString("label").ifBlank { null })
        "set_timer" -> Commands.setTimer(ctx, args.optInt("seconds").coerceAtLeast(1), args.optString("label").ifBlank { null })
        else -> "Unknown tool"
    }

    /** Runs on a background thread. */
    fun ask(text: String): String {
        val k = key()
        if (k.isBlank()) return "Please add your Gemini key in the Lucky app first."

        val contents = JSONArray()
        history.forEach { contents.put(it) }
        val userTurn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        contents.put(userTurn)

        var reply = ""
        for (round in 0 until 4) {
            val res = call(k, contents)
            val parts = res.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            if (parts.length() == 0) break
            contents.put(JSONObject().put("role", "model").put("parts", parts))

            val calls = ArrayList<JSONObject>()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"))
                else if (!p.optBoolean("thought") && p.has("text")) sb.append(p.getString("text"))
            }
            if (sb.isNotBlank()) reply = sb.toString().trim()
            if (calls.isEmpty()) break

            val responses = JSONArray()
            for (c in calls) {
                val name = c.optString("name")
                val out = runTool(name, c.optJSONObject("args") ?: JSONObject())
                responses.put(JSONObject().put("functionResponse",
                    JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
                reply = out
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }

        history.add(userTurn)
        history.add(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", reply.ifBlank { "Done." }))))
        while (history.size > 8) history.removeAt(0)
        return reply.replace(Regex("[*#_`]"), "").ifBlank { "Done." }
    }

    private fun call(k: String, contents: JSONArray): JSONObject {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system()))))
            .put("contents", contents)
            .put("tools", tools())
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
            .toString().toByteArray()

        var lastError: Exception? = null
        for (m in models) {
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", k)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) return JSONObject(txt)
                val msg = try {
                    JSONObject(txt).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    txt.take(200)
                }
                if (code == 401 || code == 403) throw Exception("Google rejected the Gemini key. Check it in the Lucky app.")
                val err = Exception("Gemini error $code: $msg")
                lastError = err
                if (code != 404 && code != 429 && code != 500 && code != 503) throw err
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: Exception("No Gemini model available right now.")
    }
}
