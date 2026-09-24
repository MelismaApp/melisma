package com.melisma.app.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release notes as the update prompt shows them, from the Markdown they are written in. */
class ReleaseNotesTest {

    /** Shaped like 2.5.1's, which is what showed as raw Markdown on a phone. */
    private val notes = """
        **Fixes for 2.5.0's Canvas. If you use Canvas, this is the version to have.**

        ### A Canvas that vanished after rotating
        Each of those switches changes where the Canvas goes.
        The player now outlives its views.

        - **Artwork could stop for a track** until the app restarted.
        - Smaller ones:
          - a Canvas that fails to play now frees its player;
          - the app no longer burns the CPU
            while reconnecting.
        1. First
        2. Second

        ---

        Download the APK below and open it.
    """.trimIndent()

    @Test
    fun `the blocks come out in order, without the footer for GitHub readers`() {
        val blocks = notesBlocks(notes)
        assertEquals(
            listOf(
                NotesBlock.Paragraph("**Fixes for 2.5.0's Canvas. If you use Canvas, this is the version to have.**"),
                NotesBlock.Heading(3, "A Canvas that vanished after rotating"),
                // Lines of one paragraph are one paragraph, so they wrap to the screen.
                NotesBlock.Paragraph("Each of those switches changes where the Canvas goes. The player now outlives its views."),
                NotesBlock.Item(0, "•", "**Artwork could stop for a track** until the app restarted."),
                NotesBlock.Item(0, "•", "Smaller ones:"),
                NotesBlock.Item(1, "◦", "a Canvas that fails to play now frees its player;"),
                NotesBlock.Item(1, "◦", "the app no longer burns the CPU while reconnecting."),
                NotesBlock.Item(0, "1.", "First"),
                NotesBlock.Item(0, "2.", "Second"),
            ),
            blocks,
        )
    }

    @Test
    fun `only the footer's rule ends the notes`() {
        // A rule between sections is content, and so is one inside code.
        val blocks = notesBlocks(
            """
            One

            ---

            Two

            ```
            ---
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(NotesBlock.Paragraph("One"), NotesBlock.Rule, NotesBlock.Paragraph("Two"), NotesBlock.Code("---")),
            blocks,
        )
    }

    @Test
    fun `tables and code keep their shape`() {
        val blocks = notesBlocks(
            """
            | | before | now |
            |---|---|---|
            | portrait | 32sp | **32sp** |

            ```
            val x = 1
              indented
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                NotesBlock.Table(listOf(listOf("", "before", "now"), listOf("portrait", "32sp", "**32sp**"))),
                NotesBlock.Code("val x = 1\n  indented"),
            ),
            blocks,
        )
    }

    @Test
    fun `emphasis, code and links lose their markers`() {
        val text = inlineMarkdown(
            "**Selected lines survived *Look up this track again*** and `arm64-v8a`, see [the notes](https://github.com/x).",
        )
        assertEquals("Selected lines survived Look up this track again and arm64-v8a, see the notes.", text.text)

        val bold = text.spanStyles.first { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("Selected lines survived Look up this track again", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("Look up this track again", text.text.substring(italic.start, italic.end))

        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://github.com/x", (link.item as LinkAnnotation.Url).url)
        assertEquals("the notes", text.text.substring(link.start, link.end))
    }

    @Test
    fun `what only looks like Markdown is left as it is`() {
        assertEquals("snake_case_name and 5 * 3 and a lone ** here", inlineMarkdown("snake_case_name and 5 * 3 and a lone ** here").text)
        // Only a web page is opened from the prompt.
        val local = inlineMarkdown("[a file](file:///sdcard/x)")
        assertEquals("a file", local.text)
        assertTrue(local.getLinkAnnotations(0, local.length).isEmpty())
    }
}
