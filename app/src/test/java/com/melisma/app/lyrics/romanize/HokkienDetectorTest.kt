package com.melisma.app.lyrics.romanize

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling Taiwanese Hokkien from Mandarin written in the same characters.
 *
 * Checked by hand against 31 real songs while it was built — Hokkien pop from 茄子蛋, 伍佰, 江蕙,
 * 五月天, 蕭煌奇, 吳宗憲 and 蔡佩軒 against Mandarin and Cantonese — which cannot live here, being
 * lyrics. These are the same kind of sentence, written for the test.
 */
class HokkienDetectorTest {

    private val hokkien = listOf(
        "我毋知影你佇佗位", "阮兜的囡仔攏足乖", "伊講明仔載欲來揣我", "你莫閣講矣", "查某囡仔真媠",
    )
    private val mandarin = listOf(
        "我不知道你在哪裡", "我們家的孩子都很乖", "他說明天要來找我", "你不要再說了", "那個女孩真漂亮",
    )

    @Test
    fun `Hokkien is told from Mandarin`() {
        assertTrue(HokkienDetector.isHokkien(hokkien))
        assertFalse(HokkienDetector.isHokkien(mandarin))
    }

    @Test
    fun `simplified Hokkien is still Hokkien`() {
        assertTrue(HokkienDetector.isHokkien(hokkien.map(HokkienWords::fold)))
    }

    @Test
    fun `credits do not count`() {
        // Written in Mandarin whatever the song is in, and several lines long on NetEase.
        val credits = List(12) { "作词 : 某某某/作曲 : 某某某/编曲 : 某某某/制作人 : 某某某" }
        assertTrue(HokkienDetector.isHokkien(credits + hokkien))
    }

    @Test
    fun `too little text decides nothing`() {
        assertFalse(HokkienDetector.isHokkien(listOf("毋知影")))
    }

    @Test
    fun `written Cantonese is not Hokkien`() {
        assertFalse(HokkienDetector.isHokkien(listOf("我唔知你喺邊度", "佢哋啲嘢好靚", "你食咗飯未呀", "我哋今日冇嘢做")))
    }
}
