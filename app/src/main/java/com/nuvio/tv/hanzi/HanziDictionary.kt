package com.nuvio.tv.hanzi

import android.content.Context
import java.util.zip.GZIPInputStream
import kotlin.math.ln
import kotlin.math.min

/** One dictionary sense group: a headword with one reading and its definitions. */
data class HanziEntry(
    val simplified: String,
    val traditional: String,
    val pinyin: String,          // tone-marked, e.g. "jiē chù"
    val definitions: List<String>,
    val hsk: Int
)

/** A piece of a subtitle line. For Chinese words, [readings] has one tone-marked syllable per character. */
data class HanziToken(
    val text: String,
    val isHan: Boolean,
    val known: Boolean,
    val readings: List<String> = emptyList()
) {
    val canLookUp: Boolean get() = isHan
}

/**
 * CC-CEDICT based dictionary with jieba-style segmentation, shared in design with the Hanzi Lens
 * IINA plugin. The word list stays as one UTF-8 blob (about 12 MB) with per-entry offsets; only the
 * headword index lives on the Java heap as strings.
 */
object HanziDictionary {
    @Volatile private var loaded = false
    private val lock = Any()

    private lateinit var blob: ByteArray
    private lateinit var lineStart: IntArray
    private lateinit var freq: IntArray
    private val index = HashMap<String, IntArray>(260_000)
    private val charReading = HashMap<String, String>(16_000)
    private val charGloss = HashMap<String, String>(16_000)
    private var totalFreq = 1.0
    private var maxLen = 1

    val isReady: Boolean get() = loaded

    /** Blocking; call from a background thread. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val assets = context.applicationContext.assets
            blob = GZIPInputStream(assets.open("hanzi/words.bin"), 1 shl 16).use { it.readBytes() }
            var lines = 0
            for (b in blob) if (b == '\n'.code.toByte()) lines++
            lineStart = IntArray(lines)
            freq = IntArray(lines)
            val building = HashMap<String, IntArray>(260_000)
            var pos = 0
            var id = 0
            var total = 0.0
            while (pos < blob.size && id < lines) {
                lineStart[id] = pos
                val t1 = indexOf(pos, TAB)
                val t2 = indexOf(t1 + 1, TAB)
                val t3 = indexOf(t2 + 1, TAB)
                val t4 = indexOf(t3 + 1, TAB)
                val t5 = indexOf(t4 + 1, TAB)
                val simp = String(blob, pos, t1 - pos, Charsets.UTF_8)
                val trad = String(blob, t1 + 1, t2 - t1 - 1, Charsets.UTF_8)
                val f = parseInt(t4 + 1, t5)
                freq[id] = f
                total += f
                addKey(building, simp, id)
                if (trad != simp) addKey(building, trad, id)
                val len = simp.codePointCount(0, simp.length)
                if (len > maxLen) maxLen = len
                pos = indexOf(t5 + 1, NL) + 1
                id++
            }
            index.putAll(building)
            totalFreq = if (total > 0) total else 1.0
            if (maxLen > 12) maxLen = 12

            GZIPInputStream(assets.open("hanzi/chars.bin")).bufferedReader(Charsets.UTF_8).useLines { seq ->
                seq.forEach { line ->
                    val p = line.split('\t')
                    if (p.size >= 2 && p[1].isNotEmpty()) charReading[p[0]] = p[1].lowercase()
                    if (p.size >= 3 && p[2].isNotEmpty()) charGloss[p[0]] = p[2]
                }
            }
            loaded = true
            android.util.Log.i("HanziLens", "Dictionary ready: ${lines} entries, ${index.size} headwords in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    private fun addKey(map: HashMap<String, IntArray>, key: String, id: Int) {
        val cur = map[key]
        map[key] = if (cur == null) intArrayOf(id) else if (cur.contains(id)) cur else cur + id
    }

    private fun indexOf(from: Int, byte: Byte): Int {
        var i = from
        while (i < blob.size && blob[i] != byte) i++
        return i
    }

    private fun parseInt(from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) {
            val d = blob[i] - '0'.code.toByte()
            if (d < 0 || d > 9) break
            v = v * 10 + d
        }
        return v
    }

    private class Raw(val simp: String, val trad: String, val pinyin: String, val defs: String, val hsk: Int)

    private fun raw(id: Int): Raw {
        val s = lineStart[id]
        val e = indexOf(s, NL)
        val p = String(blob, s, e - s, Charsets.UTF_8).split('\t')
        return Raw(p[0], p[1], p[2], p.getOrElse(3) { "" }, p.getOrElse(5) { "0" }.toIntOrNull() ?: 0)
    }

    // ------------------------------------------------------------------ lookup

    private val lowPriority =
        Regex("^(surname |variant of |old variant of |used in |see |\\(archaic\\)|erhua variant|Japanese variant|\\(old\\)|abbr\\. for)", RegexOption.IGNORE_CASE)

    /** Standalone readings for frequent polyphones, where dictionary order misleads. */
    private val preferred = mapOf(
        "长" to "chang2", "得" to "de5", "只" to "zhi3", "干" to "gan4", "幹" to "gan4", "发" to "fa1", "少" to "shao3",
        "好" to "hao3", "难" to "nan2", "教" to "jiao1", "当" to "dang1", "数" to "shu4", "便" to "bian4", "乐" to "le4",
        "大" to "da4", "看" to "kan4", "中" to "zhong1", "要" to "yao4", "把" to "ba3", "倒" to "dao4", "假" to "jia3",
        "调" to "diao4", "应" to "ying1", "传" to "chuan2", "觉" to "jue2", "的" to "de5", "地" to "de5", "着" to "zhe5",
        "了" to "le5", "还" to "hai2", "都" to "dou1", "没" to "mei2", "会" to "hui4", "么" to "me5", "長" to "chang2",
        "發" to "fa1", "樂" to "le4", "數" to "shu4", "當" to "dang1", "難" to "nan2", "還" to "hai2", "會" to "hui4",
        "覺" to "jue2", "為" to "wei4", "为" to "wei4", "著" to "zhe5", "和" to "he2", "那" to "na4", "哪" to "na3",
        "吗" to "ma5", "嗎" to "ma5", "似" to "si4", "处" to "chu4", "行" to "xing2", "重" to "zhong4", "相" to "xiang1",
        "差" to "cha4", "种" to "zhong3", "更" to "geng4", "给" to "gei3", "給" to "gei3", "说" to "shuo1", "說" to "shuo1"
    )

    private fun score(r: Raw, word: String): Int {
        var s = 0
        if (preferred[word] == r.pinyin) s += 40
        if (r.pinyin.isNotEmpty() && r.pinyin[0].isUpperCase()) s -= 40          // proper noun
        if (lowPriority.containsMatchIn(r.defs)) s -= 30
        if (r.defs.startsWith("surname")) s -= 10
        if (r.hsk > 0) s += 20 - r.hsk
        s += min(r.defs.count { it == '/' } + 1, 8)
        if (word.codePointCount(0, word.length) == 1) {
            val first = charReading[word]
            if (first != null) {
                val marked = PinyinFormat.marked(r.pinyin).lowercase()
                if (PinyinFormat.stripTones(marked) == PinyinFormat.stripTones(first)) s += 6
                if (marked == first) s += 6
            }
        }
        return s
    }

    private fun rawsFor(word: String): List<Raw> {
        val ids = index[word] ?: return emptyList()
        return ids.map { raw(it) }.sortedByDescending { score(it, word) }
    }

    fun has(word: String): Boolean = loaded && index.containsKey(word)

    /** All entries for a headword, best first. Falls back to single-character data for unknown characters. */
    fun lookup(word: String): List<HanziEntry> {
        if (!loaded) return emptyList()
        val found = rawsFor(word).map {
            HanziEntry(it.simp, it.trad, PinyinFormat.marked(it.pinyin), cleanDefs(it.defs), it.hsk)
        }
        if (found.isNotEmpty()) return found
        if (word.codePointCount(0, word.length) == 1) {
            val gloss = charGloss[word] ?: return emptyList()
            return listOf(HanziEntry(word, word, charReading[word].orEmpty(), gloss.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.take(6), 0))
        }
        return emptyList()
    }

    private val bracketPinyin = Regex("\\[([a-zA-Z0-9: ,·]+)]")
    private fun cleanDefs(defs: String): List<String> =
        defs.split('/').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("CL:") }
            .map { d -> bracketPinyin.replace(d) { m -> " (" + PinyinFormat.marked(m.groupValues[1]) + ")" }.replace(Regex("\\|[^\\s(\\[]+"), "") }

    private fun bestReading(word: String): String = rawsFor(word).firstOrNull()?.pinyin.orEmpty()

    // ------------------------------------------------------------------ segmentation

    fun isHan(cp: Int): Boolean =
        cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F || cp == 0x3007

    fun containsHan(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun wordFreq(word: String): Int {
        val ids = index[word] ?: return 0
        var f = 0
        for (i in ids) if (freq[i] > f) f = freq[i]
        return f
    }

    private fun segmentRun(run: List<String>): List<String> {
        val n = run.size
        val logTotal = ln(totalFreq)
        val unknown = -logTotal - 4
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1)
        best[n] = 0.0
        for (i in n - 1 downTo 0) {
            val w = StringBuilder()
            for (k in i until min(n, i + maxLen)) {
                w.append(run[k])
                val word = w.toString()
                val sc = if (index.containsKey(word)) {
                    val f = wordFreq(word)
                    ln(if (f > 0) f.toDouble() else if (k > i) 3.0 else 1.0) - logTotal
                } else if (k == i) unknown else continue
                val total = sc + best[k + 1]
                if (total > best[i]) { best[i] = total; back[i] = k + 1 }
            }
        }
        val out = ArrayList<String>()
        var i = 0
        while (i < n) { out.add(run.subList(i, back[i]).joinToString("")); i = back[i] }
        return out
    }

    private fun codePoints(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            out.add(text.substring(i, i + len))
            i += len
        }
        return out
    }

    /** Splits one line into words and attaches a tone-marked reading to every Chinese character. */
    fun annotate(line: String): List<HanziToken> {
        if (!loaded) return listOf(HanziToken(line, isHan = false, known = false))
        val chars = codePoints(line)
        val tokens = ArrayList<HanziToken>()
        var i = 0
        while (i < chars.size) {
            if (!isHan(chars[i].codePointAt(0))) {
                val sb = StringBuilder()
                var j = i
                while (j < chars.size && !isHan(chars[j].codePointAt(0))) { sb.append(chars[j]); j++ }
                tokens.add(HanziToken(sb.toString(), isHan = false, known = false))
                i = j
                continue
            }
            var j = i
            while (j < chars.size && isHan(chars[j].codePointAt(0))) j++
            for (word in segmentRun(chars.subList(i, j))) {
                val wc = codePoints(word)
                val known = index.containsKey(word)
                var readings: List<String> = emptyList()
                if (known) {
                    val syl = PinyinFormat.syllables(bestReading(word).lowercase())
                    if (syl.size == wc.size) readings = syl
                }
                if (readings.isEmpty()) readings = wc.map { c -> singleReading(c) }
                tokens.add(HanziToken(word, isHan = true, known = known, readings = readings))
            }
            i = j
        }
        return applySandhi(tokens)
    }

    private fun singleReading(ch: String): String {
        val r = rawsFor(ch).firstOrNull()?.pinyin
        if (!r.isNullOrEmpty()) return PinyinFormat.marked(r).lowercase()
        return charReading[ch].orEmpty()
    }

    /** Tone changes for 一 and 不 at the start of a word or standing alone (一个 yí ge, 一起 yì qǐ, 不要 bú yào). */
    private fun applySandhi(tokens: List<HanziToken>): List<HanziToken> {
        data class Ref(val token: Int, val char: Int)
        val flat = ArrayList<Ref>()
        val chars = ArrayList<String>()
        val py = ArrayList<String>()
        tokens.forEachIndexed { ti, t ->
            if (t.isHan) codePoints(t.text).forEachIndexed { ci, c -> flat.add(Ref(ti, ci)); chars.add(c); py.add(t.readings.getOrElse(ci) { "" }) }
            else { flat.add(Ref(ti, -1)); chars.add(""); py.add("") }
        }
        var changed = false
        for (i in 0 until flat.size - 1) {
            val c = chars[i]
            // 不 changes before a 4th tone anywhere (不见不散: bú jiàn bú sàn); 一 only when it starts a word.
            if (c != "一" && c != "不") continue
            if (c == "一" && flat[i].char != 0) continue
            if (flat[i].char < 0) continue
            if (flat[i + 1].char < 0 || py[i + 1].isEmpty()) continue
            val nextTone = PinyinFormat.toneOf(py[i + 1])
            if (c == "不") {
                if (PinyinFormat.toneOf(py[i]) != 5) { py[i] = if (nextTone == 4) "bú" else "bù"; changed = true }
                continue
            }
            if (i > 0 && chars[i - 1].isNotEmpty() && "第十〇零一二三四五六七八九百千万".contains(chars[i - 1])) continue
            if ("一二三四五六七八九十〇零月日号".contains(chars[i + 1])) continue
            py[i] = if (nextTone == 4 || nextTone == 5) "yí" else "yì"
            changed = true
        }
        if (!changed) return tokens
        val out = tokens.map { it.copy(readings = it.readings.toMutableList()) }
        flat.forEachIndexed { i, ref -> if (ref.char >= 0) (out[ref.token].readings as MutableList<String>)[ref.char] = py[i] }
        return out
    }

    private const val TAB: Byte = 9
    private const val NL: Byte = 10
}

/** Numeric pinyin ("ni3 hao3", "lu:4") to tone-marked text, and helpers around tone marks. */
object PinyinFormat {
    private val marks = mapOf(
        'a' to "āáǎàa", 'e' to "ēéěèe", 'i' to "īíǐìi", 'o' to "ōóǒòo", 'u' to "ūúǔùu", 'v' to "ǖǘǚǜü"
    )
    private val syllable = Regex("^([a-zA-ZüÜ:]+?)([1-5])?$")

    fun syllableMarked(syl: String): String {
        val m = syllable.find(syl) ?: return syl
        val base = m.groupValues[1].replace("u:", "v").replace("U:", "V").replace('ü', 'v').replace('Ü', 'V')
        val tone = m.groupValues[2].toIntOrNull() ?: 5
        val lower = base.lowercase()
        var idx = when {
            lower.contains('a') -> lower.indexOf('a')
            lower.contains('e') -> lower.indexOf('e')
            lower.contains("ou") -> lower.indexOf('o')
            else -> lower.indexOfLast { it in "iouv" }
        }
        if (idx < 0) idx = -1
        val out = StringBuilder()
        for (i in base.indices) {
            val ch = base[i]
            val lc = ch.lowercaseChar()
            var rep = if (lc == 'v') "ü" else lc.toString()
            val row = marks[lc]
            if (i == idx && row != null) rep = row[tone - 1].toString()
            out.append(if (ch != lc) rep.uppercase() else rep)
        }
        return out.toString()
    }

    fun syllables(numeric: String): List<String> =
        numeric.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { syllableMarked(it) }

    fun marked(numeric: String): String = syllables(numeric).joinToString(" ")

    fun toneOf(marked: String): Int = when {
        marked.any { it in "āēīōūǖ" } -> 1
        marked.any { it in "áéíóúǘ" } -> 2
        marked.any { it in "ǎěǐǒǔǚ" } -> 3
        marked.any { it in "àèìòùǜ" } -> 4
        marked.isEmpty() -> 0
        else -> 5
    }

    private val plain = mapOf(
        'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a', 'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e',
        'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i', 'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
        'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u', 'ǖ' to 'ü', 'ǘ' to 'ü', 'ǚ' to 'ü', 'ǜ' to 'ü'
    )
    fun stripTones(marked: String): String = buildString { marked.forEach { append(plain[it] ?: it) } }
}
