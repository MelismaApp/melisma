package com.melisma.app.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.melisma.app.AppContainer
import com.melisma.app.MelismaApp
import com.melisma.app.media.MediaNotificationListener

/**
 * One connection to a car, for as long as it lasts.
 *
 * Three things happen here that the phone's activity normally does, because a car screen can be the
 * *only* thing that ever opens this app — plug the phone in on the way to work and no window of ours
 * is ever created:
 *
 * - **Start watching.** The media repository and the notification listener are brought up exactly as
 *   `MainActivity.onStart` brings them up. Without it, Melisma on a car screen would sit on "nothing
 *   playing" while the stereo played, because nothing had ever asked the system what was going on.
 * - **Hold the idle timer off.** The app releases its notification listener after ten quiet minutes,
 *   and quiet means "no window of ours on screen and nothing playing". A car session is not a window,
 *   so without saying so the app would stand itself down mid-drive and the lyrics would stop for good.
 * - **Own the composition.** A `ComposeView` drawn onto the car's surface needs a `LifecycleOwner`
 *   and a `SavedStateRegistryOwner`, and a session is the right lifetime for both: the UI on the car
 *   screen should exist for exactly as long as the car is connected.
 */
class MelismaSession : Session(), SavedStateRegistryOwner {

    private val savedState = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedState.savedStateRegistry

    init {
        // At construction, and not in onCreateScreen: a registry is restored while its owner is still
        // initialising, which is what an activity does inside onCreate. There is nothing to restore —
        // a car session has no state worth carrying across a reconnect — but the registry has to have
        // been restored before a ComposeView is allowed to use it.
        savedState.performRestore(null)
    }

    /** Shared with the screens, which need to know whether there is a surface to draw the app on. */
    private val surface = CarSurfaceState()

    private val container: AppContainer
        get() = (carContext.applicationContext as MelismaApp).container

    override fun onCreateScreen(intent: Intent): Screen {
        val app = container

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                app.carConnected = true
                // The listener may have been stood down hours ago; nothing rebinds it on its own.
                MediaNotificationListener.rebind(carContext)
                app.media.refresh()
                app.media.start()
            }

            override fun onStop(owner: LifecycleOwner) {
                // Not stopping the media repository, for the same reason the activity does not:
                // the idle watch needs a live playback signal to decide with, and a frozen snapshot
                // reads as "still playing" for ever.
                app.carConnected = false
            }
        })

        // Asked for once, here, rather than when a screen appears: the host answers whenever it is
        // ready, and a callback registered later can miss the surface it already offered.
        runCatching {
            carContext.getCarService(AppManager::class.java)
                .setSurfaceCallback(CarScreenSurface(this, app, surface))
        }

        // The warning is the root screen rather than a dialog over the lyrics, which makes it the one
        // thing that cannot be skipped by the host restoring a previous screen — and makes the back
        // gesture from the lyrics land on it rather than closing the app.
        return RoadWarningScreen(carContext, surface)
    }
}
