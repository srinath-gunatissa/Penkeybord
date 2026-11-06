package com.penkeyboard.app

import android.content.Context
import org.json.JSONArray
import kotlin.math.ln

/** Learns the words you use, and keeps clipboard history. Stored only on the tablet. */
class WordMemory(context: Context) {
    private val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val counts = HashMap<String, Int>()

    init {
        prefs.getString(Prefs.WORDS, null)?.lineSequence()?.forEach { line ->
            val tab = line.lastIndexOf('\t')
            if (tab > 0) counts[line.substring(0, tab)] = line.substring(tab + 1).toIntOrNull() ?: 0
        }
    }

    fun count(word: String) = counts[key(word)] ?: 0

    fun learn(text: String) {
        for (w in text.split(' ', '\n')) {
            val k = key(w)
            if (k.length < 2) continue
            counts[k] = (counts[k] ?: 0) + 1
        }
        if (counts.size > 3000) {
            counts.entries.sortedBy { it.value }.take(counts.size - 2500).forEach { counts.remove(it.key) }
        }
        prefs.edit().putString(Prefs.WORDS,
            counts.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    /** Move words you use often a little higher in the list. */
    fun rank(options: List<String>): List<String> =
        options.withIndex()
            .sortedBy { (i, w) -> i - 1.2 * ln(1.0 + count(w)) }
            .map { it.value }

    fun forgetAll() {
        counts.clear(); prefs.edit().remove(Prefs.WORDS).apply()
    }

    private fun key(w: String) = w.trim { !it.isLetterOrDigit() }.lowercase()

    // ---- clipboard history ----
    fun clips(): List<String> {
        val arr = try { JSONArray(prefs.getString(Prefs.CLIPS, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addClip(text: String) {
        if (text.isBlank()) return
        val list = clips().filter { it != text }.toMutableList()
        list.add(0, text)
        prefs.edit().putString(Prefs.CLIPS, JSONArray(list.take(25)).toString()).apply()
    }

    fun removeClip(text: String) {
        prefs.edit().putString(Prefs.CLIPS, JSONArray(clips().filter { it != text }).toString()).apply()
    }
}
