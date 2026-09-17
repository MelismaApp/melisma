package com.melisma.app.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The Android Auto entry point.
 *
 * Melisma on a car screen is the same app answering a different question. On a phone it draws the
 * whole song, syllable by syllable, because you can look at a phone. In a car it gets two lines and
 * no animation, because you cannot — see [CarGlance], which is where that limit is enforced rather
 * than merely intended.
 *
 * **What this cannot be, and why.** The Car App Library admits apps in seven categories —
 * navigation, POI, IoT, weather, media, messaging, calling — and there is no category for lyrics.
 * `MEDIA` is for apps that browse and play their own catalogue through a `MediaBrowserService`;
 * Melisma plays nothing and owns nothing, it reads whatever another app is already playing. So this
 * declares `IOT`, the closest thing to a companion-app slot, and the consequence is stated plainly
 * in the documentation: it will never be a Play-listed Android Auto app, and it appears on the car
 * screen only with Android Auto's own developer setting for unknown sources switched on. For an app
 * distributed as an APK that is the same audience it already has.
 */
class MelismaCarAppService : CarAppService() {

    override fun onCreateSession(): Session = MelismaSession()

    /**
     * Which hosts may drive this app.
     *
     * The service has to be exported for Android Auto to reach it at all, so this is the check that
     * replaces a permission: only a host whose signature is on the library's own allowlist gets to
     * open a session. A debug build allows any host, which is what makes the Desktop Head Unit
     * usable while developing — and is exactly why it is gated on the debuggable flag rather than
     * left in for convenience.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
}
