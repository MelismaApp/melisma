package com.melisma.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.melisma.app.AppContainer
import com.melisma.app.MelismaApp
import com.melisma.app.R
import com.melisma.app.media.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The words, on a car screen.
 *
 * Everything about *which* words is decided by [CarGlance]; this only turns the answer into rows and
 * decides when to ask the host to redraw. Two rows, and the second one says "Next" out loud rather
 * than relying on colour or position to make the difference — a template's colour spans are the
 * host's to honour or ignore, and a driver working out which of two identical-looking lines is the
 * current one is a driver reading rather than glancing.
 *
 * **Why this polls.** Nothing emits when a playhead advances — a media session publishes a position
 * and a timestamp, and everything after that is arithmetic. So the screen recomputes on a slow tick
 * and asks the host to redraw only when the *rendered content* has actually changed, which for a
 * song is once a line: a few times a minute. That matters because template updates are throttled by
 * the host, and an app that invalidates on a timer rather than on a change gets throttled for
 * nothing.
 */
class CarLyricsScreen(
    carContext: CarContext,
    private val surface: CarSurfaceState,
) : Screen(carContext) {

    private val container: AppContainer
        get() = (carContext.applicationContext as MelismaApp).container

    /** What the host is currently showing, so a redraw can be skipped when nothing has changed. */
    private var shown: Content? = null

    private data class Content(
        val glance: CarGlance,
        val title: String,
        val artist: String,
        val transport: Transport,
        val playing: Boolean,
        /**
         * Whether the app is drawing itself on the car's surface.
         *
         * Part of the compared value rather than read at draw time, so a surface arriving a second
         * after the screen opens swaps the template instead of leaving the fallback up until the next
         * line changes.
         */
        val onSurface: Boolean,
    )

    init {
        lifecycleScope.launch {
            // STARTED rather than CREATED: a screen with another pushed over it is not visible, and
            // recomputing lyrics nobody can see is exactly the kind of work this app tries not to do.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastRedrawAt = 0L
                while (true) {
                    val next = compose()
                    val now = System.currentTimeMillis()
                    if (next != shown && now - lastRedrawAt >= MIN_REDRAW_GAP_MS) {
                        lastRedrawAt = now
                        invalidate()
                    }
                    delay(TICK_MS)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Composed fresh rather than read from the field the tick loop wrote: the host asks for a
        // template at moments of its own choosing, and answering with whatever was true a second ago
        // would put a stale line on the screen.
        val content = compose()
        shown = content
        return content.toTemplate()
    }

    private fun compose(): Content {
        val snapshot = container.media.snapshot.value
        val settings = container.settings.current
        val playback = snapshot.playback

        return Content(
            glance = CarGlance.of(
                state = container.lyrics.state.value,
                hasTrack = snapshot.hasTrack,
                permissionGranted = container.media.permissionGranted.value,
                // The same sum the phone uses, offset included: two screens disagreeing about where
                // the song is would be worse than either being wrong on its own.
                positionMs = playback.currentMs() + settings.syncOffsetMs,
                settings = settings,
            ),
            title = snapshot.track?.title?.takeIf { it.isNotBlank() }
                ?: carContext.getString(R.string.app_name),
            artist = snapshot.track?.artist?.takeIf { it.isNotBlank() }.orEmpty(),
            transport = snapshot.transport,
            playing = playback.isPlaying,
            // The phone's own switch wins: somebody who asked for the plain screen gets it even where
            // the full one would work.
            onSurface = surface.live.value &&
                !settings.carSimpleScreen &&
                // MapWithContentTemplate is @RequiresCarApi(7) while this app admits hosts from
                // level 1, and a surface arrives from level 5 — so there is a real window where the
                // app can be drawing happily and still have no template able to carry it. Asking the
                // host what it understands is the difference between the fallback and a rejection.
                carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7,
        )
    }

    // ---- turning an answer into a template ---------------------------------

    private fun Content.toTemplate(): Template {
        // With a surface, the app is already drawing the lyrics itself and the template's job is only
        // the chrome around them: what is playing, and the controls. Without one — an older host, or
        // one that declines a surface — the same screen falls back to two lines of text, which is a
        // complete answer rather than a degraded one.
        if (onSurface) return onSurfaceTemplate()
        return fallback()
    }

    private fun Content.onSurfaceTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(title)
                    .apply { if (artist.isNotEmpty()) addText(artist) }
                    .build(),
            )

        val content = PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.BACK)
            .build()

        val template = MapWithContentTemplate.Builder()
            .setContentTemplate(content)

        transportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    private fun Content.fallback(): Template = when (glance) {
        is CarGlance.Now -> lyrics(glance)
        CarGlance.Looking -> PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()

        CarGlance.Silent -> message(R.string.car_silent, R.string.car_silent_body)
        CarGlance.None -> message(R.string.car_none, R.string.car_none_body)
        CarGlance.Untimed -> message(R.string.car_untimed, R.string.car_untimed_body)
        CarGlance.Offline -> message(R.string.car_offline, R.string.car_offline_body)
        CarGlance.NoPermission -> message(R.string.car_permission, R.string.car_permission_body)
        is CarGlance.Failed -> MessageTemplate.Builder(glance.message)
            .setTitle(carContext.getString(R.string.car_failed))
            .setHeaderAction(Action.BACK)
            .build()
    }


    private fun Content.lyrics(glance: CarGlance.Now): Template {
        val pane = Pane.Builder()

        val current = Row.Builder()
            .setTitle(
                glance.line?.takeIf { it.isNotBlank() }
                    ?: carContext.getString(R.string.car_instrumental),
            )
        glance.beneath?.let { current.addText(it) }
        pane.addRow(current.build())

        glance.next?.takeIf { it.isNotBlank() }?.let { next ->
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_next, next))
                    .build(),
            )
        }

        val template = PaneTemplate.Builder(pane.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)

        transportStrip()?.let { template.setActionStrip(it) }
        return template.build()
    }

    /**
     * The transport row, or nothing at all.
     *
     * Null rather than an empty strip, and that is not tidiness: `ActionStrip.Builder.build()` throws
     * "Action strip must contain at least one action", and a session advertising only `ACTION_SEEK_TO`
     * has `transport.any` true while earning no buttons at all — which would have taken down both car
     * templates for that one player.
     */
    private fun Content.transportStrip(): ActionStrip? {
        val wanted = carTransportFor(transport)
        if (wanted.isEmpty()) return null

        val strip = ActionStrip.Builder()
        for (button in wanted) {
            strip.addAction(
                when (button) {
                    CarTransport.PREVIOUS -> action(R.drawable.ic_car_previous, null) {
                        container.media.skipPrevious()
                    }

                    CarTransport.PLAY_PAUSE -> action(
                        if (playing) R.drawable.ic_car_pause else R.drawable.ic_car_play,
                        if (playing) R.string.car_pause else R.string.car_play,
                    ) { container.media.togglePlayPause() }

                    CarTransport.NEXT -> action(R.drawable.ic_car_next, null) {
                        container.media.skipNext()
                    }
                },
            )
        }
        return strip.build()
    }

    /**
     * One button, with a title only where the templates allow one.
     *
     * This started out as icon *and* title on all three, for the screen reader — `Action` has no
     * content description of its own, so a title is the only label there is. The library says no:
     * `PaneTemplate.setActionStrip` validates against `ACTIONS_CONSTRAINTS_SIMPLE`, which permits
     * exactly **one** action with a custom title and throws on the second. So the one title goes to
     * play/pause, the button whose meaning changes and the one most worth announcing, and the skips
     * are icon-only. Both templates in use here allow at least one, so the same row works in each.
     */
    private fun action(icon: Int, label: Int?, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .apply { label?.let { setTitle(carContext.getString(it)) } }
            .setOnClickListener {
                onClick()
                // The player answers by publishing a new state, which the next tick picks up — but
                // a button that looks unchanged for half a second reads as one that did not work.
                invalidate()
            }
            .build()

    private fun message(title: Int, body: Int): Template =
        MessageTemplate.Builder(carContext.getString(body))
            .setTitle(carContext.getString(title))
            .setHeaderAction(Action.BACK)
            .build()

    private companion object {

        /**
         * How often the playhead is re-examined. Half a second is imperceptible against a lyric line
         * that lasts three, and it is a comparison of two small values rather than any drawing.
         */
        const val TICK_MS = 500L

        /**
         * A floor between redraws, whatever the words do.
         *
         * The host throttles template updates and will drop or penalise an app that pushes them too
         * fast. A line every three seconds is nowhere near that, but a fast verse can put two lines
         * inside a second, and the fix is to let the second one wait for the next tick.
         */
        const val MIN_REDRAW_GAP_MS = 1_000L
    }
}

/** A transport button, named before it becomes an `Action` the host can validate. */
internal enum class CarTransport { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * Which transport buttons a session has earned, in the order they belong on screen.
 *
 * Separated out because the two mistakes it prevents are both invisible until a particular player is
 * playing: a session offering only a seek would otherwise produce an empty action strip, which
 * throws, and the count decides whether a title can be afforded at all.
 */
internal fun carTransportFor(transport: Transport): List<CarTransport> = buildList {
    if (transport.skipPrevious) add(CarTransport.PREVIOUS)
    if (transport.playPause) add(CarTransport.PLAY_PAUSE)
    if (transport.skipNext) add(CarTransport.NEXT)
}
