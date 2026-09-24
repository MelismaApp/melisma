package com.melisma.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A release's notes, rendered from the Markdown GitHub holds them in.
 *
 * Only what release notes use: headings, paragraphs, nested lists, tables, code, emphasis and
 * links. Anything else shows as its text.
 */
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { notesBlocks(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val gap = when {
                previous == null -> 0.dp
                block is NotesBlock.Item && previous is NotesBlock.Item -> 4.dp
                block is NotesBlock.Heading -> 16.dp
                else -> 10.dp
            }
            Box(Modifier.padding(top = gap)) { NotesBlockView(block) }
        }
    }
}

@Composable
private fun NotesBlockView(block: NotesBlock) {
    when (block) {
        is NotesBlock.Heading -> Text(
            inlineMarkdown(block.text),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = if (block.level <= 2) 16.sp else 14.sp,
            lineHeight = if (block.level <= 2) 21.sp else 19.sp,
            fontWeight = FontWeight.Bold,
        )

        is NotesBlock.Paragraph -> Text(inlineMarkdown(block.text), color = BODY, fontSize = 13.sp, lineHeight = 19.sp)

        is NotesBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            Text(block.marker, color = BODY, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.width(18.dp))
            Text(inlineMarkdown(block.text), color = BODY, fontSize = 13.sp, lineHeight = 19.sp)
        }

        is NotesBlock.Code -> Text(
            block.text,
            color = BODY,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )

        is NotesBlock.Table -> Column(Modifier.fillMaxWidth()) {
            block.rows.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    row.forEach { cell ->
                        Text(
                            inlineMarkdown(cell),
                            color = BODY,
                            fontSize = 12.sp,
                            fontWeight = if (index == 0) FontWeight.SemiBold else null,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                        )
                    }
                }
            }
        }

        NotesBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
    }
}

private val BODY = Color.White.copy(alpha = 0.7f)
private val BOLD = SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.9f))
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val CODE = SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.08f))
private val LINK = TextLinkStyles(SpanStyle(color = Color(0xFF9DB8FF), textDecoration = TextDecoration.Underline))

internal sealed interface NotesBlock {
    data class Heading(val level: Int, val text: String) : NotesBlock
    data class Paragraph(val text: String) : NotesBlock
    data class Item(val depth: Int, val marker: String, val text: String) : NotesBlock
    data class Code(val text: String) : NotesBlock
    data class Table(val rows: List<List<String>>) : NotesBlock
    data object Rule : NotesBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

/**
 * The blocks of [markdown], without its footer.
 *
 * Every release ends with a rule and then a note for someone reading it on GitHub — "Download the
 * APK below" — which is wrong in the app, so that rule and what follows are left out. Any other
 * rule is content.
 */
internal fun notesBlocks(markdown: String): List<NotesBlock> {
    val lines = markdown.replace("\r\n", "\n").lines()
    val footer = footerStart(lines)
    val body = if (footer >= 0) lines.subList(0, footer) else lines

    val blocks = mutableListOf<NotesBlock>()
    var paragraph: StringBuilder? = null
    var code: StringBuilder? = null
    var table: MutableList<List<String>>? = null

    fun endParagraph() {
        paragraph?.let { blocks += NotesBlock.Paragraph(it.toString()) }
        paragraph = null
    }
    fun endTable() {
        table?.let { blocks += NotesBlock.Table(it) }
        table = null
    }

    for (line in body) {
        val fence = line.trimStart().startsWith("```")
        code?.let { open ->
            if (fence) {
                blocks += NotesBlock.Code(open.toString().trimEnd('\n'))
                code = null
            } else {
                open.append(line).append('\n')
            }
            continue
        }
        if (fence) {
            endParagraph(); endTable()
            code = StringBuilder()
            continue
        }

        val trimmed = line.trim()
        if (trimmed.startsWith("|")) {
            endParagraph()
            if (!TABLE_DIVIDER.matches(trimmed)) {
                val cells = trimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                (table ?: mutableListOf<List<String>>().also { table = it }) += cells
            }
            continue
        }
        endTable()

        if (trimmed.isEmpty()) {
            endParagraph()
            continue
        }
        HEADING.matchEntire(trimmed)?.let {
            endParagraph()
            blocks += NotesBlock.Heading(it.groupValues[1].length, it.groupValues[2])
            continue
        }
        if (RULE.matches(line)) {
            endParagraph()
            blocks += NotesBlock.Rule
            continue
        }
        BULLET.matchEntire(line)?.let {
            endParagraph()
            val depth = depthOf(it.groupValues[1])
            blocks += NotesBlock.Item(depth, if (depth == 0) "•" else "◦", it.groupValues[2].trim())
            continue
        }
        NUMBERED.matchEntire(line)?.let {
            endParagraph()
            blocks += NotesBlock.Item(depthOf(it.groupValues[1]), "${it.groupValues[2]}.", it.groupValues[3].trim())
            continue
        }

        val text = trimmed.removePrefix(">").trim()
        val last = blocks.lastOrNull()
        when {
            paragraph != null -> paragraph!!.append(' ').append(text)
            // A line that carries on a list item rather than starting a paragraph under it.
            line.first().isWhitespace() && last is NotesBlock.Item ->
                blocks[blocks.lastIndex] = last.copy(text = last.text + " " + text)
            else -> paragraph = StringBuilder(text)
        }
    }
    code?.let { blocks += NotesBlock.Code(it.toString().trimEnd('\n')) }
    endParagraph()
    endTable()
    return blocks
}

/** The line of the last rule outside code, when the footer follows it; otherwise -1. */
private fun footerStart(lines: List<String>): Int {
    var inCode = false
    var lastRule = -1
    lines.forEachIndexed { index, line ->
        if (line.trimStart().startsWith("```")) inCode = !inCode
        else if (!inCode && RULE.matches(line)) lastRule = index
    }
    if (lastRule < 0) return -1
    val next = lines.drop(lastRule + 1).firstOrNull { it.isNotBlank() }?.trim() ?: return lastRule
    return if (next.startsWith("Download the APK", ignoreCase = true)) lastRule else -1
}

/** Two spaces or a tab per level, the way the notes indent. */
private fun depthOf(indent: String): Int = indent.replace("\t", "  ").length / 2

/** Emphasis, code, strikethrough and links within one block. */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString { appendInline(text) }

private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) append(plain.toString())
        plain.clear()
    }
    fun wrap(style: SpanStyle, inner: String) {
        flush()
        withStyle(style) { appendInline(inner) }
    }

    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && !text[i + 1].isLetterOrDigit() -> {
                plain.append(text[i + 1])
                i += 2
            }

            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    withStyle(CODE) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }

            text.startsWith("**", i) || text.startsWith("__", i) || text.startsWith("~~", i) -> {
                val delimiter = text.substring(i, i + 2)
                var end = text.indexOf(delimiter, i + 2)
                // `***` closing bold around a closing `*`: the last two are the bold's.
                if (end > 0 && text.getOrNull(end + 2) == delimiter[0]) end++
                if (end <= i + 2) {
                    plain.append(delimiter)
                    i += 2
                } else {
                    wrap(if (delimiter == "~~") STRIKE else BOLD, text.substring(i + 2, end))
                    i = end + 2
                }
            }

            (c == '*' || c == '_') && opensEmphasis(text, i) -> {
                val end = closesEmphasis(text, c, i + 1)
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    wrap(ITALIC, text.substring(i + 1, end))
                    i = end + 1
                }
            }

            c == '[' -> {
                val middle = text.indexOf("](", i + 1)
                val end = if (middle < 0) -1 else text.indexOf(')', middle + 2)
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    val label = text.substring(i + 1, middle)
                    val url = text.substring(middle + 2, end).trim()
                    flush()
                    // Only a web page is worth opening from here.
                    if (url.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(url, LINK)) { appendInline(label) }
                    } else {
                        appendInline(label)
                    }
                    i = end + 1
                }
            }

            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flush()
}

/** A lone `*` or `_` before a word; `_` inside a word, as in snake_case, is left alone. */
private fun opensEmphasis(text: String, at: Int): Boolean {
    val next = text.getOrNull(at + 1) ?: return false
    if (next.isWhitespace() || next == text[at]) return false
    return text[at] == '*' || text.getOrNull(at - 1)?.isLetterOrDigit() != true
}

private fun closesEmphasis(text: String, delimiter: Char, from: Int): Int {
    var j = from
    while (j < text.length) {
        val found = text.indexOf(delimiter, j)
        if (found < 0) return -1
        val doubled = text.getOrNull(found + 1) == delimiter || text.getOrNull(found - 1) == delimiter
        val afterWord = !text[found - 1].isWhitespace()
        val endsWord = delimiter == '*' || text.getOrNull(found + 1)?.isLetterOrDigit() != true
        if (!doubled && afterWord && endsWord) return found
        j = found + if (doubled) 2 else 1
    }
    return -1
}
