package com.melisma.app.car

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the car screen's drawing surface is doing, shared between the session and the screens.
 *
 * Two facts, both of which the template has to react to:
 *
 * - **Whether there is a surface at all.** A host that does not grant one — an older Android Auto,
 *   or a head unit that declines — leaves the app with templates and nothing else. That is a
 *   supported outcome rather than a failure, so the screen watches this and falls back to the
 *   two-line pane instead of showing an empty box where the lyrics should be.
 * - **Which part of it is actually visible.** The host draws its own chrome over the surface and
 *   says where, so the lyrics are laid out inside that rectangle rather than underneath a header.
 */
class CarSurfaceState {

    private val _live = MutableStateFlow(false)

    /** True once the host has handed over a surface and it is being drawn on. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private val _visibleArea = MutableStateFlow(Rect())

    /**
     * The unobstructed part of the surface, in surface pixels.
     *
     * Empty until the host says otherwise, which the content treats as "all of it" — a first frame
     * drawn edge to edge and corrected a moment later is better than one that is not drawn.
     */
    val visibleArea: StateFlow<Rect> = _visibleArea.asStateFlow()

    internal fun onLive(live: Boolean) {
        _live.value = live
        if (!live) _visibleArea.value = Rect()
    }

    internal fun onVisibleArea(area: Rect) {
        _visibleArea.value = Rect(area)
    }
}
