package com.melisma.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading the Ministry's spreadsheet, in the shape it is published in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HokkienDictionaryTest {

    private fun cell(text: String) = "<table:table-cell office:value-type=\"string\"><text:p>$text</text:p></table:table-cell>"
    private fun number(value: Int) =
        "<table:table-cell office:value-type=\"float\" office:value=\"$value\"><text:p>$value</text:p></table:table-cell>"
    private fun row(vararg cells: String) = "<table:table-row>${cells.joinToString("")}</table:table-row>"
    private fun table(name: String, vararg rows: String) =
        "<table:table table:name=\"$name\">${rows.joinToString("")}</table:table>"

    private fun spreadsheet(vararg tables: String): ByteArrayInputStream {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-content
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
            <office:body><office:spreadsheet>${tables.joinToString("")}</office:spreadsheet></office:body>
            </office:document-content>"""
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray())
            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(xml.toByteArray())
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private val entries = table(
        "詞目",
        row(cell("詞目id"), cell("詞目類型"), cell("漢字"), cell("羅馬字")),
        row(number(1), cell("主詞目"), cell("一【替】"), cell("tsi̍t")),
        row(number(213), cell("主詞目"), cell("大人"), cell("tāi-jîn/tāi-lîn")),
        row(number(214), cell("主詞目"), cell("大人"), cell("tuā-lâng")),
        row(number(94), cell("主詞目"), cell("八字跤"), cell("pat-jī-kha")),
        // Not a word of characters: skipped.
        row(number(95), cell("附錄"), cell("A型"), cell("a-hîng")),
    )

    @Test
    fun `entries, their other readings and their other spellings`() {
        val words = MoeSpreadsheet.read(
            spreadsheet(
                entries,
                // A sheet that does not matter, between the ones that do.
                table("例句", row(number(1), cell("大人"), cell("tuā-lâng"))),
                table("又唸作", row(cell("詞目id"), cell("漢字"), cell("羅馬字")), row(number(94), cell("八字跤"), cell("peh-jī-kha"))),
                table("異用字", row(number(94), cell("八字跤"), cell("八字骹")), row(number(213), cell("大人"), cell("大儂"))),
            ),
        )
        assertEquals(listOf("tsi̍t"), words["一"])
        // Two entries for one word keep both, in the dictionary's order.
        assertEquals(listOf("tāi-jîn", "tāi-lîn", "tuā-lâng"), words["大人"])
        assertEquals(listOf("pat-jī-kha", "peh-jī-kha"), words["八字跤"])
        assertEquals(words["八字跤"], words["八字骹"])
        assertEquals(words["大人"], words["大儂"])
        assertFalse("A型" in words)
        assertFalse("詞目id" in words)
    }

    @Test
    fun `repeated and empty cells keep the columns in place`() {
        val words = MoeSpreadsheet.read(
            spreadsheet(
                table(
                    "詞目",
                    row(number(7), "<table:table-cell table:number-columns-repeated=\"1\"/>", cell("心肝"), cell("sim-kuann")),
                ),
            ),
        )
        assertEquals(listOf("sim-kuann"), words["心肝"])
    }
}
