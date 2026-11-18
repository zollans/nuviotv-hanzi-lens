package com.nuvio.tv.hanzi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.roundToInt

private class Piece(
    val main: TextLayoutResult,
    val reading: TextLayoutResult?,
    val width: Float,
    val row: Int,
    val tokenInRow: Int,
    var x: Float = 0f
)

private class LaidOut(
    val pieces: List<Piece>,
    val rowWidths: List<Float>,
    val rowCount: Int,
    val readingHeight: Float,
    val mainHeight: Float,
    val mainBaseline: Float,
    val translation: TextLayoutResult?,
    val height: Float
)

private fun layoutLine(
    line: HanziLine,
    measurer: TextMeasurer,
    hanziPx: Float,
    maxWidth: Float,
    textColor: Color,
    bold: Boolean,
    toSp: (Float) -> androidx.compose.ui.unit.TextUnit
): LaidOut {
    val mainStyle = TextStyle(fontSize = toSp(hanziPx), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, color = textColor, fontFamily = FontFamily.Default)
    val readStyle = TextStyle(fontSize = toSp(hanziPx * 0.42f), fontWeight = FontWeight.SemiBold, color = textColor, fontFamily = FontFamily.Default)
    val transStyle = TextStyle(fontSize = toSp(hanziPx * 0.56f), fontWeight = FontWeight.Medium, color = textColor.copy(alpha = textColor.alpha * 0.94f), fontFamily = FontFamily.Default, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    val gap = hanziPx * 0.06f
    val pieces = ArrayList<Piece>()
    val rowWidths = ArrayList<Float>()
    var visualRow = 0
    var readingHeight = 0f
    var mainHeight = 0f

    for (row in line.rows) {
        // Build the unwrapped run of pieces for this subtitle line, remembering word boundaries.
        class Draft(val piece: Piece, val wordStart: Boolean)
        val drafts = ArrayList<Draft>()
        row.forEachIndexed { ti, token ->
            if (token.isHan) {
                var ci = 0
                var i = 0
                while (i < token.text.length) {
                    val cp = token.text.codePointAt(i)
                    val len = Character.charCount(cp)
                    val ch = token.text.substring(i, i + len)
                    val main = measurer.measure(ch, mainStyle)
                    val py = token.readings.getOrElse(ci) { "" }
                    val reading = if (py.isNotEmpty()) measurer.measure(py, readStyle) else null
                    val w = max(main.size.width.toFloat(), (reading?.size?.width ?: 0) + hanziPx * 0.16f) + gap
                    drafts.add(Draft(Piece(main, reading, w, 0, ti), wordStart = ci == 0))
                    if (reading != null) readingHeight = max(readingHeight, reading.size.height.toFloat())
                    mainHeight = max(mainHeight, main.size.height.toFloat())
                    i += len; ci++
                }
            } else if (token.text.isNotBlank() || drafts.isNotEmpty()) {
                val text = if (token.text.isBlank()) " " else token.text
                val main = measurer.measure(text, mainStyle)
                drafts.add(Draft(Piece(main, null, main.size.width.toFloat(), 0, ti), wordStart = true))
                mainHeight = max(mainHeight, main.size.height.toFloat())
            }
        }
        // Wrap between words into the fewest rows that fit, keeping the rows about equally wide
        // (no orphaned last word). A word is never split unless it is wider than a whole row.
        val words = ArrayList<IntRange>()
        var ws = 0
        for (k in 1..drafts.size) if (k == drafts.size || drafts[k].wordStart) { if (k > ws) words.add(ws until k); ws = k }
        // Closing punctuation stays with the word before it.
        val merged = ArrayList<IntRange>()
        for (w in words) {
            val first = drafts[w.first].piece
            val isClosing = first.reading == null && w.count() == 1 && first.main.layoutInput.text.text.trim().let { it.isNotEmpty() && it.all { c -> c in "，。！？；：、,.!?;:）)》」』”’…" } }
            if (isClosing && merged.isNotEmpty()) merged[merged.lastIndex] = merged.last().first..w.last else merged.add(w)
        }
        fun widthOf(r: IntRange): Float { var t = 0f; for (k in r) t += drafts[k].piece.width; return t }
        val total = widthOf(0 until drafts.size)
        // Try the fewest rows first; if breaking only between words needs one more row than the
        // widths suggest, re-balance for that count instead of leaving a short last row.
        var breaks: List<Int> = emptyList()
        var rowsWanted = max(1, kotlin.math.ceil(total / maxWidth).toInt())
        while (true) {
            val limit = kotlin.math.min(maxWidth, total / rowsWanted * 1.1f)
            val cut = ArrayList<Int>()
            var acc = 0f
            for (w in merged) {
                val ww = widthOf(w)
                if (acc > 0f && (acc + ww > maxWidth || (acc + ww > limit && cut.size < rowsWanted - 1))) { cut.add(w.first); acc = 0f }
                acc += ww
            }
            breaks = cut
            if (cut.size + 1 <= rowsWanted || rowsWanted >= merged.size) break
            rowsWanted++
        }
        var rowStart = 0
        for (end in breaks + drafts.size) {
            if (end <= rowStart) continue
            var x = 0f
            for (k in rowStart until end) {
                val p = drafts[k].piece
                pieces.add(Piece(p.main, p.reading, p.width, visualRow, p.tokenInRow, x))
                x += p.width
            }
            rowWidths.add(x)
            visualRow++
            rowStart = end
        }
    }
    val translation = if (line.translation.isNotBlank()) measurer.measure(line.translation, transStyle, constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1))) else null
    val rowHeight = readingHeight + mainHeight * 0.96f
    val height = rowHeight * visualRow + (translation?.size?.height ?: 0)
    // Latin and digit runs use a font with a shorter line box than CJK; align everything on one baseline.
    val mainBaseline = pieces.maxOfOrNull { it.main.firstBaseline } ?: 0f
    return LaidOut(pieces, rowWidths, visualRow, readingHeight, mainHeight, mainBaseline, translation, height)
}

private fun DrawScope.outlined(layout: TextLayoutResult, at: Offset, outline: Color, strokePx: Float) {
    if (strokePx > 0f) {
        drawText(layout, color = outline, topLeft = at + Offset(0f, strokePx * 0.35f), alpha = 0.45f, drawStyle = Stroke(width = strokePx * 2.2f, join = StrokeJoin.Round))
        drawText(layout, color = outline, topLeft = at, drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round))
    }
    // The fill must be requested explicitly: a null draw style keeps the stroke from the pass above.
    drawText(layout, topLeft = at, drawStyle = Fill)
}

/**
 * Draws the Chinese line with pinyin over each character, the translation line of bilingual
 * subtitles underneath, and (in lookup mode) the selected word and its dictionary card.
 *
 * @param bottomInset distance from the bottom of the screen to the bottom of the subtitle block, given the screen height
 */
@Composable
fun HanziSubtitleOverlay(
    line: HanziLine?,
    secondary: String,
    settings: HanziSettings,
    lookup: HanziLookup?,
    textSizePx: Float,
    bottomInset: (heightPx: Int) -> Float,
    textColor: Color,
    outlineColor: Color,
    bold: Boolean,
    modifier: Modifier = Modifier
) {
    // A bilingual cue's own translation wins; otherwise the English track's current line is used.
    // English lines with no Chinese on screen (songs, signs) are still shown.
    if (!settings.active) return
    @Suppress("NAME_SHADOWING")
    val secondary = if (settings.english) secondary else ""
    @Suppress("NAME_SHADOWING")
    val line = when {
        line != null && line.rows.isNotEmpty() -> when {
            !settings.english -> line.copy(translation = "")
            line.translation.isBlank() && secondary.isNotBlank() -> line.copy(translation = secondary)
            else -> line
        }
        secondary.isNotBlank() -> HanziLine(emptyList(), secondary, "")
        else -> return
    }.let { l ->
        // Pinyin off: keep the words (lookup still works) but drop the readings row.
        if (settings.pinyin) l else l.copy(rows = l.rows.map { row -> row.map { it.copy(readings = emptyList()) } })
    }
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val hanziPx = textSizePx * 1.22f
        val laid = remember(line, hanziPx, widthPx, textColor, bold) {
            layoutLine(line, measurer, hanziPx, widthPx * 0.9f, textColor, bold) { px -> with(density) { px.toSp() } }
        }
        val rowHeight = laid.readingHeight + laid.mainHeight * 0.96f
        val top = heightPx - bottomInset(constraints.maxHeight) - laid.height
        val stroke = hanziPx * 0.085f

        val selected = lookup?.let { line.lookupTargets.getOrNull(it.target) }
        var selectedRect: Rect? = null
        if (selected != null) {
            // The selected word may wrap; highlight every visual row it touches, anchor the card on the first.
            val mine = laid.pieces.filter { p -> p.tokenInRow == selected.second && rowOfSource(line, laid, p) == selected.first }
            mine.groupBy { it.row }.values.firstOrNull()?.let { ps ->
                val rowLeft = (widthPx - laid.rowWidths[ps.first().row]) / 2f
                selectedRect = Rect(rowLeft + ps.first().x, top + ps.first().row * rowHeight, rowLeft + ps.last().x + ps.last().width, top + (ps.first().row + 1) * rowHeight)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            if (selected != null) {
                laid.pieces.filter { p -> p.tokenInRow == selected.second && rowOfSource(line, laid, p) == selected.first }
                    .groupBy { it.row }.values.forEach { ps ->
                        val rowLeft = (widthPx - laid.rowWidths[ps.first().row]) / 2f
                        val pad = hanziPx * 0.08f
                        drawRoundRect(
                            color = Color(0xE60A84FF),
                            topLeft = Offset(rowLeft + ps.first().x - pad, top + ps.first().row * rowHeight - pad * 0.5f),
                            size = Size(ps.last().x + ps.last().width - ps.first().x + pad * 2, rowHeight + pad),
                            cornerRadius = CornerRadius(hanziPx * 0.18f)
                        )
                    }
            }
            for (p in laid.pieces) {
                val rowLeft = (widthPx - laid.rowWidths[p.row]) / 2f
                val y = top + p.row * rowHeight
                val cx = rowLeft + p.x + p.width / 2f
                p.reading?.let { outlined(it, Offset(cx - it.size.width / 2f, y + laid.readingHeight - it.size.height), outlineColor, stroke * 0.62f) }
                outlined(p.main, Offset(cx - p.main.size.width / 2f, y + laid.readingHeight - laid.mainHeight * 0.04f + laid.mainBaseline - p.main.firstBaseline), outlineColor, stroke)
            }
            laid.translation?.let {
                outlined(it, Offset((widthPx - it.size.width) / 2f, top + laid.rowCount * rowHeight), outlineColor, stroke * 0.7f)
            }
        }

        val rect = selectedRect
        if (lookup != null && rect != null) {
            val cardWidth = with(density) { 440.dp.toPx() }
            var cardSize by remember { mutableStateOf(IntSize.Zero) }
            val margin = with(density) { 24.dp.toPx() }
            val x = (rect.center.x - cardWidth / 2f).coerceIn(margin, max(margin, widthPx - cardWidth - margin))
            // Sit above the whole subtitle block, so a word on a lower row never hides the rows above it.
            val y = (top - with(density) { 14.dp.toPx() } - cardSize.height).coerceAtLeast(margin)
            HanziLookupCard(
                lookup = lookup,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(440.dp)
                    .onSizeChanged { cardSize = it }
            )
        }
    }
}

/** Which source row (subtitle line) a laid-out piece came from: visual rows are assigned in source order. */
private fun rowOfSource(line: HanziLine, laid: LaidOut, piece: Piece): Int {
    // Pieces are appended row by row; find the source row by walking the pieces list.
    var source = 0
    var lastToken = -1
    for (p in laid.pieces) {
        if (p.tokenInRow < lastToken) source++
        lastToken = p.tokenInRow
        if (p === piece) return source
    }
    return source.coerceAtMost(line.rows.lastIndex)
}

@Composable
private fun HanziLookupCard(lookup: HanziLookup, modifier: Modifier = Modifier) {
    val label = Color(0xF2FFFFFF)
    val secondary = Color(0x99EBEBF5)
    val tertiary = Color(0x66EBEBF5)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .background(Color(0xF21C1C1E), shape)
            .border(1.dp, Color(0x24FFFFFF), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        val first = lookup.entries.firstOrNull()
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(lookup.word, style = TextStyle(color = label, fontSize = 34.sp, fontWeight = FontWeight.Medium))
            val alt = first?.let { if (it.traditional != lookup.word) it.traditional else if (it.simplified != lookup.word) it.simplified else null }
            if (alt != null) {
                Spacer(Modifier.width(10.dp))
                BasicText("($alt)", style = TextStyle(color = secondary, fontSize = 22.sp), modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            if (first != null && first.hsk > 0) {
                BasicText(
                    "HSK " + if (first.hsk >= 7) "7–9" else first.hsk.toString(),
                    style = TextStyle(color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = 6.dp).background(Color(0x1FFFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (first == null) {
            Spacer(Modifier.height(6.dp))
            BasicText("No dictionary entry", style = TextStyle(color = secondary, fontSize = 17.sp))
        }
        lookup.entries.take(2).forEachIndexed { ei, entry ->
            Spacer(Modifier.height(if (ei == 0) 2.dp else 10.dp))
            BasicText(entry.pinyin, style = TextStyle(color = label, fontSize = 20.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(4.dp))
            entry.definitions.take(if (ei == 0) 4 else 2).forEachIndexed { i, d ->
                Row(Modifier.padding(top = 2.dp)) {
                    BasicText("${i + 1}", style = TextStyle(color = tertiary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(20.dp).padding(top = 2.dp))
                    BasicText(d, style = TextStyle(color = label, fontSize = 17.sp, lineHeight = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Character breakdown for multi-character words.
        val chars = remember(lookup.word) { lookup.word.codePoints().toArray().map { String(Character.toChars(it)) } }
        if (chars.size > 1) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.height(1.dp).fillMaxSize().background(Color(0x1AFFFFFF)))
            Spacer(Modifier.height(8.dp))
            chars.take(4).forEach { c ->
                val e = remember(c) { HanziDictionary.lookup(c).firstOrNull() }
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(c, style = TextStyle(color = label, fontSize = 19.sp))
                    BasicText(e?.pinyin.orEmpty(), style = TextStyle(color = label, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    BasicText(e?.definitions?.firstOrNull().orEmpty(), style = TextStyle(color = secondary, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        BasicText("◀ ▶  word      OK / Back  resume", style = TextStyle(color = tertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}
