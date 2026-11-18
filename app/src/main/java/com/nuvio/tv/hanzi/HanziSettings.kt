package com.nuvio.tv.hanzi

import android.content.Context

/**
 * Hanzi Lens choices, remembered per show (or film). A show that has never been configured starts
 * with the lens off and all three aids on, so picking "Hanzi Lens" in the subtitle menu once is all
 * it takes, and picking plain "Chinese" (or another language) turns it off for that show only.
 */
data class HanziSettings(
    val active: Boolean = false,
    val pinyin: Boolean = true,
    val english: Boolean = true,
    val lookup: Boolean = true
) {
    companion object {
        private const val FILE = "hanzi_lens"
        private fun key(showId: String?, name: String) = "show:${showId ?: "_"}:$name"

        fun load(context: Context, showId: String?): HanziSettings {
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return HanziSettings(
                active = p.getBoolean(key(showId, "active"), false),
                pinyin = p.getBoolean(key(showId, "pinyin"), true),
                english = p.getBoolean(key(showId, "english"), true),
                lookup = p.getBoolean(key(showId, "lookup"), true)
            )
        }

        fun save(context: Context, showId: String?, s: HanziSettings) {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(key(showId, "active"), s.active)
                .putBoolean(key(showId, "pinyin"), s.pinyin)
                .putBoolean(key(showId, "english"), s.english)
                .putBoolean(key(showId, "lookup"), s.lookup)
                .apply()
        }
    }
}
