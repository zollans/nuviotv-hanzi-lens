package com.nuvio.tv.hanzi

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * When the selected subtitle is Chinese and the file also carries an English text track, the
 * English one is decoded as well, on a second text renderer, so its lines can be shown under the
 * pinyin line. Both tracks come out of the stream the player is already reading: no extra download.
 */
object HanziSecondTrack {
    private val bitmapSubtitles = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)

    fun isChinese(f: Format): Boolean {
        val lang = f.language.orEmpty().lowercase()
        if (lang.startsWith("zh") || lang.startsWith("chi") || lang.startsWith("zho") || lang.startsWith("cmn") || lang.startsWith("yue")) return true
        val label = f.label.orEmpty()
        return Regex("chinese|mandarin|cantonese|中文|简|簡|繁|國語|国语|粵|粤", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }

    private fun isEnglish(f: Format): Boolean {
        val lang = f.language.orEmpty().lowercase()
        return lang == "en" || lang.startsWith("en-") || lang.startsWith("eng") ||
            (lang.isEmpty() && Regex("\\benglish\\b", RegexOption.IGNORE_CASE).containsMatchIn(f.label.orEmpty()))
    }

    private fun score(f: Format): Int {
        var s = 0
        val label = f.label.orEmpty()
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || label.contains("forced", true)) s -= 4
        if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || Regex("sdh|\\bcc\\b|hearing", RegexOption.IGNORE_CASE).containsMatchIn(label)) s -= 2
        if (f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) s += 1
        return s
    }

    /**
     * Track type reported by the second text renderer. It is not TRACK_TYPE_TEXT on purpose: the
     * player applies the user's subtitle choice to every text renderer after track selection, which
     * would clear a second one. A custom type is left alone.
     */
    const val RENDERER_TYPE: Int = C.TRACK_TYPE_CUSTOM_BASE + 71

    /** Wraps a text renderer so it reports [RENDERER_TYPE]. */
    class Renderer(base: androidx.media3.exoplayer.Renderer) : androidx.media3.exoplayer.ForwardingRenderer(base) {
        private val caps = object : RendererCapabilities by base.capabilities {
            override fun getTrackType(): Int = RENDERER_TYPE
        }
        override fun getTrackType(): Int = RENDERER_TYPE
        override fun getCapabilities(): RendererCapabilities = caps
    }

    /** Returns [definitions] with an English text track added on the second renderer, when that applies. */
    fun attachEnglish(
        enabled: Boolean,
        info: MappedTrackInfo,
        supports: Array<out Array<out IntArray>>,
        definitions: Array<ExoTrackSelection.Definition?>,
        params: androidx.media3.common.TrackSelectionParameters
    ): Array<ExoTrackSelection.Definition?> {
        if (!enabled || params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return definitions
        val spare = (0 until info.rendererCount).firstOrNull { info.getRendererType(it) == RENDERER_TYPE } ?: return definitions
        val text = (0 until info.rendererCount).filter { info.getRendererType(it) == C.TRACK_TYPE_TEXT }

        // The main subtitle: an explicit choice from the subtitle menu (applied by the player after
        // this point), otherwise whatever default selection picked.
        var primary = -1
        var chosenGroup: androidx.media3.common.TrackGroup? = null
        var chosenTrack = 0
        for (o in params.overrides.values) {
            if (o.mediaTrackGroup.type != C.TRACK_TYPE_TEXT || o.trackIndices.isEmpty()) continue
            val r = text.firstOrNull { info.getTrackGroups(it).indexOf(o.mediaTrackGroup) != -1 } ?: continue
            primary = r; chosenGroup = o.mediaTrackGroup; chosenTrack = o.trackIndices[0]
        }
        if (chosenGroup == null) {
            if (params.overrides.values.any { it.mediaTrackGroup.type == C.TRACK_TYPE_TEXT }) return definitions   // "None" chosen
            primary = text.firstOrNull { definitions.getOrNull(it) != null } ?: return definitions
            val d = definitions[primary] ?: return definitions
            chosenGroup = d.group; chosenTrack = d.tracks.firstOrNull() ?: return definitions
        }
        val chosenFormat = chosenGroup.getFormat(chosenTrack)
        if (!isChinese(chosenFormat) || chosenFormat.sampleMimeType in bitmapSubtitles || chosenFormat.codecs in bitmapSubtitles) return definitions

        // Every text group is mapped to the first text renderer, so candidates are looked up there.
        val groups = info.getTrackGroups(primary)
        var best: ExoTrackSelection.Definition? = null
        var bestScore = Int.MIN_VALUE
        for (g in 0 until groups.length) {
            val group = groups[g]
            if (group == chosenGroup) continue
            for (t in 0 until group.length) {
                val f = group.getFormat(t)
                if (!isEnglish(f) || f.sampleMimeType in bitmapSubtitles || f.codecs in bitmapSubtitles) continue
                if (RendererCapabilities.getFormatSupport(supports[primary][g][t]) != C.FORMAT_HANDLED) continue
                val sc = score(f)
                if (sc > bestScore) { bestScore = sc; best = ExoTrackSelection.Definition(group, t) }
            }
        }
        val english = best
        if (english == null) { android.util.Log.i("HanziLens", "second track: the file has no English text track"); return definitions }
        android.util.Log.i("HanziLens", "second track: English '${english.group.getFormat(english.tracks[0]).label ?: ""}' alongside Chinese '${chosenFormat.label ?: ""}'")
        return definitions.copyOf().also { it[spare] = english }
    }
}
