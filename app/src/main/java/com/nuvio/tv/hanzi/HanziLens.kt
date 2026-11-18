package com.nuvio.tv.hanzi

import android.content.Context
import androidx.media3.common.text.Cue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Chinese subtitle currently on screen, split into rows of words with readings. */
data class HanziLine(
    val rows: List<List<HanziToken>>,
    val translation: String,
    val source: String
) {
    /** Words that can be looked up, in reading order, as (row, index-in-row). */
    val lookupTargets: List<Pair<Int, Int>> by lazy {
        rows.flatMapIndexed { r, row -> row.mapIndexedNotNull { i, t -> if (t.canLookUp) r to i else null } }
    }
}

data class HanziLookup(val target: Int, val entries: List<HanziEntry>, val word: String)

/**
 * Hanzi Lens for the player: takes Chinese subtitle cues away from the stock renderer and publishes
 * them as annotated lines (pinyin over every character), plus the word-lookup state driven by the remote.
 */
class HanziLens {
    private val mutableLine = MutableStateFlow<HanziLine?>(null)
    val line: StateFlow<HanziLine?> = mutableLine.asStateFlow()
    private val mutableLookup = MutableStateFlow<HanziLookup?>(null)
    val lookup: StateFlow<HanziLookup?> = mutableLookup.asStateFlow()

    private val mutableSecondary = MutableStateFlow("")
    /** The line currently showing on the file's English track, decoded alongside the Chinese one. */
    val secondary: StateFlow<String> = mutableSecondary.asStateFlow()

    fun showSecondary(cues: List<Cue>) {
        val text = cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            // sound descriptions and speaker labels of hearing-impaired tracks
            .replace(Regex("\\[[^\\]]*]|\\([^)]*\\)|[♪♫]+"), "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
        // Two speakers keep their line break; a single sentence split over two rows is joined.
        mutableSecondary.value = if (text.any { it.startsWith("-") || it.startsWith("–") }) text.joinToString("\n") else text.joinToString(" ")
    }

    private val mutableSettings = MutableStateFlow(HanziSettings())
    /** Per-show choices; see [HanziSettings]. */
    val settings: StateFlow<HanziSettings> = mutableSettings.asStateFlow()
    private var appContext: Context? = null
    private var showId: String? = null

    /** Loads the saved choices for this show. Call once the player knows what it is playing. */
    fun attach(context: Context, showId: String?) {
        appContext = context.applicationContext
        this.showId = showId
        mutableSettings.value = HanziSettings.load(context, showId)
    }

    fun update(change: (HanziSettings) -> HanziSettings) {
        val next = change(mutableSettings.value)
        if (next == mutableSettings.value) return
        mutableSettings.value = next
        appContext?.let { HanziSettings.save(it, showId, next) }
        if (!next.active || !next.lookup) mutableLookup.value = null
    }

    val enabled: Boolean get() = mutableSettings.value.active
    val lookupAllowed: Boolean get() = mutableSettings.value.active && mutableSettings.value.lookup
    val isLookingUp: Boolean get() = mutableLookup.value != null
    val hasLine: Boolean get() = mutableLine.value?.lookupTargets?.isNotEmpty() == true

    /** Returns the cues this class did not take. Chinese cues are shown by [line] instead. */
    fun take(cues: List<Cue>): List<Cue> {
        if (!enabled || !HanziDictionary.isReady) { publish(null); return cues }
        val mine = ArrayList<String>()
        val rest = ArrayList<Cue>(cues.size)
        for (cue in cues) {
            val text = cue.text
            if (text != null && cue.bitmap == null && HanziDictionary.containsHan(text)) mine.add(text.toString()) else rest.add(cue)
        }
        publish(if (mine.isEmpty()) null else mine.joinToString("\n"))
        return rest
    }

    fun clear() { publish(null); mutableSecondary.value = "" }

    private fun publish(source: String?) {
        val current = mutableLine.value
        if (source == null) {
            if (current != null) { mutableLine.value = null; mutableLookup.value = null }
            return
        }
        if (current?.source == source) return
        mutableLookup.value = null
        val zh = ArrayList<List<HanziToken>>()
        val other = ArrayList<String>()
        for (raw in source.split('\n')) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            // Bilingual subtitles carry the translation as separate lines without Chinese.
            if (HanziDictionary.containsHan(l)) zh.add(HanziDictionary.annotate(l)) else other.add(l)
        }
        mutableLine.value = HanziLine(zh, other.joinToString(" "), source)
    }

    // ------------------------------------------------------------------ lookup (remote)

    /** Starts lookup on the first word. Returns false when there is nothing to look up. */
    fun enterLookup(): Boolean {
        val l = mutableLine.value ?: return false
        if (l.lookupTargets.isEmpty()) return false
        select(l, 0)
        return true
    }

    fun move(delta: Int) {
        val l = mutableLine.value ?: return
        val cur = mutableLookup.value ?: return
        val n = l.lookupTargets.size
        select(l, ((cur.target + delta) % n + n) % n)
    }

    fun exitLookup() { mutableLookup.value = null }

    private fun select(l: HanziLine, target: Int) {
        val (r, i) = l.lookupTargets[target]
        val word = l.rows[r][i].text
        mutableLookup.value = HanziLookup(target, HanziDictionary.lookup(word), word)
    }

    companion object {
        /** Loads the dictionary once per process; cheap to call again. Blocking. */
        fun warmUp(context: Context) {
            // Never let the dictionary take the player down: without it, subtitles simply render as stock.
            try {
                HanziDictionary.ensureLoaded(context)
            } catch (t: Throwable) {
                android.util.Log.e("HanziLens", "Dictionary failed to load; pinyin subtitles are off", t)
            }
        }
    }
}
