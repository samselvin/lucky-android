package com.sam.lucky

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/** Phone actions + fast offline understanding of simple alarm / timer commands. */
object Commands {

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            ctx.startActivity(i)
            "Alarm set for ${fmt(hour, minute)}."
        } catch (e: Exception) {
            "I couldn't set the alarm. ${e.message ?: ""}"
        }
    }

    fun setTimer(ctx: Context, seconds: Int, label: String?): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!label.isNullOrBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return try {
            ctx.startActivity(i)
            "Timer set for ${duration(seconds)}."
        } catch (e: Exception) {
            "I couldn't set the timer. ${e.message ?: ""}"
        }
    }

    fun fmt(h: Int, m: Int): String {
        val ap = if (h < 12) "AM" else "PM"
        val hh = if (h % 12 == 0) 12 else h % 12
        return String.format("%d:%02d %s", hh, m, ap)
    }

    private fun duration(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h hour" + if (h > 1) "s" else "")
        if (m > 0) parts.add("$m minute" + if (m > 1) "s" else "")
        if (sec > 0) parts.add("$sec second" + if (sec > 1) "s" else "")
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    /** Returns a reply if the command was handled on the phone, or null to ask the AI. */
    fun tryLocal(ctx: Context, text: String): String? {
        val t = " " + text.lowercase()
            .replace("a.m.", "am").replace("p.m.", "pm")
            .replace(" a m", " am").replace(" p m", " pm")
            .replace("o'clock", "") + " "

        if (t.contains("alarm") || t.contains("wake me")) {
            // "in 10 minutes" style -> let the AI work out the clock time
            if (t.contains("minute") || t.contains("hour") || t.contains(" in ")) return null
            val m = Regex("""(\d{1,2})(?:[:.\s](\d{2}))?\s*(am|pm)?""").find(t) ?: return null
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3]
            if (h > 23 || min > 59) return null
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (ap.isEmpty() && h < 12 && (t.contains("evening") || t.contains("night") || t.contains("afternoon"))) h += 12
            return setAlarm(ctx, h, min, null)
        }

        if (t.contains("timer")) {
            var total = 0
            Regex("""(\d+)\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)""").findAll(t).forEach {
                val n = it.groupValues[1].toInt()
                val u = it.groupValues[2]
                total += when {
                    u.startsWith("h") -> n * 3600
                    u.startsWith("m") -> n * 60
                    else -> n
                }
            }
            if (total <= 0) return null
            return setTimer(ctx, total, null)
        }
        return null
    }
}
