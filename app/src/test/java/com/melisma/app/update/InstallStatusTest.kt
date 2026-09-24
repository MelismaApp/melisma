package com.melisma.app.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.melisma.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** What the prompt shows after Android answers an install session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallStatusTest {

    private lateinit var context: Application
    private lateinit var updater: Updater

    private val release = AvailableRelease(
        versionName = "0.2.0",
        notes = "",
        apkUrl = "https://example.invalid/melisma.apk",
        apkSizeBytes = 4,
        pageUrl = "https://example.invalid/releases/0.2.0",
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "updates").deleteRecursively()
        updater = Updater(
            context,
            SettingsStore(context),
            fetchLatest = { release },
            updatableInPlace = true,
            fetchApk = { _, target, _ -> target.writeText("apk!") },
            handOver = { true },
        )
        updater.downloadAndInstall(release)
    }

    @Test
    fun `Android's own screen is shown when it wants the user after all`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        updater.onInstallStatus(PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm)

        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(context).nextStartedActivity?.action)
        assertEquals(Updater.State.ReadyToInstall(release), updater.state.value)
    }

    @Test
    fun `declining on that screen leaves Install to press again`() {
        updater.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, "User rejected permissions", null)
        assertEquals(Updater.State.ReadyToInstall(release), updater.state.value)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun `a refusal says so, with Android's reason`() {
        updater.onInstallStatus(PackageInstaller.STATUS_FAILURE_CONFLICT, "signatures do not match", null)
        val state = updater.state.value
        assertTrue(state is Updater.State.Failed)
        assertTrue((state as Updater.State.Failed).message.endsWith(": signatures do not match"))
    }
}
