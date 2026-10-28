package com.melisma.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Installing an update through Shizuku, as far as a unit test can see it.
 *
 * Shizuku itself cannot be here — it is another app, holding a privilege granted on a real
 * device — so what is checked is everything around it: that it is only tried when the user asked
 * for it, and that nothing it does wrong can leave an update uninstallable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuInstallTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore

    private val release = AvailableRelease(
        versionName = "0.3.0",
        notes = "",
        apkUrl = "https://example.invalid/melisma.apk",
        apkSizeBytes = 4,
        pageUrl = "https://example.invalid/releases/0.3.0",
    )

    /** What each installer was asked to do. */
    private val privileged = ArrayList<File>()
    private val ordinary = ArrayList<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        settings.setShizukuUpdates(false)
        privileged.clear()
        ordinary.clear()
        File(context.cacheDir, "updates").deleteRecursively()
    }

    /** @param refusal what Shizuku says went wrong, or null for a successful install. */
    private fun updater(refusal: String?, handOverSucceeds: Boolean = true) = Updater(
        context,
        settings,
        fetchLatest = { release },
        updatableInPlace = true,
        fetchApk = { _, target, _ -> target.writeText("apk!") },
        handOver = { file ->
            ordinary += file
            handOverSucceeds
        },
        privilegedInstall = { file ->
            privileged += file
            refusal
        },
    )

    @Test
    fun `switched off, Shizuku is not asked`() = runBlocking {
        updater(refusal = null).downloadAndInstall(release)

        assertTrue(privileged.isEmpty())
        assertEquals(1, ordinary.size)
    }

    @Test
    fun `switched on, Shizuku installs it and Android is never involved`() = runBlocking {
        settings.setShizukuUpdates(true)
        val updater = updater(refusal = null)
        updater.downloadAndInstall(release)

        assertEquals(1, privileged.size)
        assertTrue(ordinary.isEmpty())
        // Still the state the app is left in: the install replaces the process from here.
        assertTrue(updater.state.value is Updater.State.ReadyToInstall)
    }

    @Test
    fun `when Shizuku cannot, the normal installer still gets the update`() = runBlocking {
        settings.setShizukuUpdates(true)
        val updater = updater(refusal = "Shizuku is not running")
        updater.downloadAndInstall(release)

        assertEquals(1, privileged.size)
        assertEquals(privileged, ordinary)
        assertTrue(updater.state.value is Updater.State.ReadyToInstall)
    }

    @Test
    fun `both failing is reported, not swallowed`() = runBlocking {
        settings.setShizukuUpdates(true)
        val updater = updater(refusal = "no permission", handOverSucceeds = false)
        updater.downloadAndInstall(release)

        val failed = updater.state.value as Updater.State.Failed
        assertTrue(failed.message, failed.message.contains("Could not start the install"))
    }

    @Test
    fun `the setting is off until asked for, and remembered after`() {
        assertFalse(SettingsStore(context).current.shizukuUpdates)

        settings.setShizukuUpdates(true)
        assertTrue(SettingsStore(context).current.shizukuUpdates)
    }

    @Test
    fun `without Shizuku the setting is not offered, and nothing throws`() {
        val updater = updater(refusal = null)

        // Robolectric has no Shizuku app and no binder: the state every phone without it is in.
        assertFalse(updater.shizukuPresent)
        assertFalse(updater.shizukuGranted)
    }

    @Test
    fun `nothing to install is refused rather than handed to pm`() {
        assertEquals("nothing to install", PrivilegedInstallerService().install(null, 0L, null))
    }

    @Test
    fun `a bind is answered once, by whoever gets there first`() {
        val answers = ArrayList<String>()
        val connection = ShizukuInstall.Connection()
        connection.onBound = { answers += if (it == null) "nothing" else "binder" }

        // The bind failed and Shizuku called back anyway, which is the order that used to
        // resume the same continuation twice.
        connection.give(null)
        connection.onServiceDisconnected(null)
        connection.give(null)

        assertEquals(listOf("nothing"), answers)
    }

    /**
     * `pm install` would take whatever package the file declares, so the check that it is this
     * app happens first — before Shizuku, and before the permission that would allow it.
     */
    @Test
    fun `a download that is not this app is refused before Shizuku is involved`() = runBlocking {
        val notAnApk = File(context.cacheDir, "someone-elses.apk").apply { writeText("not an apk") }

        val refusal = ShizukuInstall(context).install(notAnApk)

        assertTrue(refusal, refusal!!.startsWith("the download is not Melisma"))
    }
}
