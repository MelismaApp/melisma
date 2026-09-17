package com.melisma.app.car

import com.melisma.app.lyrics.LyricsState
import com.melisma.app.lyrics.model.LineRole
import com.melisma.app.lyrics.model.LyricLine
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.model.LyricsKind
import com.melisma.app.settings.Settings
import com.melisma.app.settings.TranslationSource

/**
 * What a car screen should say, right now.
 *
 * Deliberately a value rather than a template: everything about which words belong on a car screen
 * is decided here, in a pure function of the playhead and the settings, and the screen only turns
 * the answer into rows. That is what makes this testable at all — the alternative is a template
 * builder that can only be judged by plugging a phone into a car.
 *
 * This is the *plain* car screen: two lines, never more, enforced by there being nowhere in the type
 * to put a third. It is what a car gets when the host will not hand the app a surface to draw on, and
 * what the phone's own "plain screen in the car" switch asks for — where the full version draws the
 * real renderer instead, animation and all. See [CarLyricsContent].
 */
sealed interface CarGlance {

    /** No player is running, or the one that is has nothing to say. */
    data object Silent : CarGlance

    /** Something is playing and the lyrics are still being fetched. */
    data object Looking : CarGlance

    /** Nobody has these words. */
    data object None : CarGlance

    /** There are words but no timings, so there is no "now" to show. */
    data object Untimed : CarGlance

    /** No connection to look them up with. */
    data object Offline : CarGlance

    /** Notification access has not been granted, which cannot be fixed from the car. */
    data object NoPermission : CarGlance

    data class Failed(val message: String) : CarGlance

    /**
     * The words being sung, and the ones coming.
     *
     * @param line null during an instrumental passage or an intro — there is nothing being sung,
     *   and inventing something to fill the row would be a lie the driver has to read to discount.
     * @param beneath the translation of [line], when translation is switched on.
     * @param next the next line with words in it, or null at the end of the song.
     * @param instrumental true when the song is between vocal lines.
     */
    data class Now(
        val line: String?,
        val beneath: String?,
        val next: String?,
        val instrumental: Boolean,
    ) : CarGlance

    companion object {

        /**
         * @param positionMs the playhead, with the user's sync offset already applied — the car
         *   screen has to agree with the phone about where the song is, and the offset is part of
         *   that answer rather than a rendering detail.
         */
        fun of(
            state: LyricsState,
            hasTrack: Boolean,
            permissionGranted: Boolean,
            positionMs: Long,
            settings: Settings,
        ): CarGlance = when {
            !permissionGranted -> NoPermission
            !hasTrack -> Silent
            else -> when (state) {
                LyricsState.Idle, LyricsState.Loading -> Looking
                LyricsState.NotFound -> None
                LyricsState.Offline -> Offline
                is LyricsState.Failed -> Failed(state.message)
                is LyricsState.Loaded -> state.document.glance(positionMs, settings)
            }
        }

        private fun LyricsDocument.glance(positionMs: Long, settings: Settings): CarGlance {
            if (kind == LyricsKind.STATIC || !isSynced) return Untimed

            val at = (positionMs.coerceAtLeast(0L)).toInt()
            val activeIndex = activeIndexAt(at)
            val active = lines.getOrNull(activeIndex)

            // An interlude is the renderer's three breathing dots. On a car screen it is simply the
            // truth that nobody is singing, and a row saying so is better than the last line left
            // up for thirty seconds pretending to be current.
            val instrumental = active == null || active.role == LineRole.INTERLUDE
            val sung = active?.takeIf { it.role != LineRole.INTERLUDE }

            val romanize = settings.showRomanization
            val translate = settings.translationSource != TranslationSource.OFF

            return Now(
                line = sung?.display(romanize),
                beneath = sung?.translated?.takeIf { translate && it.isNotBlank() },
                next = nextVocalAfter(activeIndex)?.display(romanize),
                instrumental = instrumental,
            )
        }

        /**
         * The line the playhead is inside.
         *
         * Mirrors the renderer's own choice deliberately, including skipping background vocals:
         * they sit inside their lead line's window, so letting one win would flip the row between a
         * line and its own backing track. Two screens disagreeing about which line is current is
         * worse than either answer.
         */
        private fun LyricsDocument.activeIndexAt(positionMs: Int): Int {
            var result = -1
            for ((index, line) in lines.withIndex()) {
                if (line.role == LineRole.BACKGROUND) continue
                if (line.startMs <= positionMs) result = index else break
            }
            return result
        }

        private fun LyricsDocument.nextVocalAfter(index: Int): LyricLine? =
            lines.drop((index + 1).coerceAtLeast(0))
                .firstOrNull { it.role == LineRole.LEAD && it.text.isNotBlank() }

        /**
         * The text to put on the screen for one line.
         *
         * Romanization is honoured because a driver who switched it on cannot read the original —
         * showing kanji to someone who asked for romaji is showing them nothing. Providers that
         * romanize per syllable leave the line-level field empty, so it is composed from the
         * syllables here rather than falling back to a script the reader cannot use.
         */
        internal fun LyricLine.display(romanize: Boolean): String {
            if (!romanize) return text
            romanized?.takeIf { it.isNotBlank() }?.let { return it }
            return composeRomanization() ?: text
        }

        private fun LyricLine.composeRomanization(): String? {
            if (syllables.none { !it.romanized.isNullOrBlank() }) return null
            return buildString {
                for ((index, syllable) in syllables.withIndex()) {
                    val piece = syllable.romanized?.takeIf { it.isNotBlank() } ?: syllable.text
                    // The analyser says where words begin in a script written without spaces; where
                    // it has no opinion, a syllable that does not continue the previous one starts
                    // a new word. Without this, 君の声が reads as one long unbroken word.
                    val startsWord = syllable.romanizedStartsWord ?: !syllable.partOfWord
                    if (index > 0 && startsWord) append(' ')
                    append(piece)
                }
            }.trim().takeIf { it.isNotEmpty() }
        }
    }
}
