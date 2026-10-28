package com.melisma.app.update

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.melisma.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume

/**
 * Installing an update through Shizuku, when the user has set that up.
 *
 * Android waives its install confirmation for an app updating itself, but Play Protect asks
 * about an APK it has not seen regardless, so an update still needs a tap. Shizuku is the way
 * out that does not involve pretending to be something: the user grants it an ADB-level
 * identity themselves, deliberately, and an install made with that identity needs no more
 * answers — the same thing as installing from a terminal, which is where this app's APK comes
 * from anyway.
 *
 * Entirely optional and off until switched on. Everything here reports a failure rather than
 * raising one, because every failure has the same answer: fall back to the normal installer.
 */
class ShizukuInstall(private val context: Context) {

    /** Whether Shizuku is running and talking to us. */
    val running: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Whether the user has let Melisma use it. */
    val granted: Boolean
        get() = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Whether Shizuku is on the device at all, which is what decides if the setting is shown. */
    val installed: Boolean
        get() = running || PACKAGES.any { name ->
            runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
        }

    /**
     * Ask Shizuku for permission. The answer arrives in Shizuku's own dialog, and is read
     * afterwards from [granted] rather than reported back here.
     */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "could not ask Shizuku for permission: ${it.message}") }
    }

    /**
     * Install [apk]. Returns null when it worked, otherwise why it did not.
     *
     * On success the app is replaced and this process ends, so nothing here runs afterwards.
     */
    suspend fun install(apk: File): String? = withContext(Dispatchers.IO) {
        if (!granted) return@withContext "Shizuku has not given Melisma permission"
        val connection = Connection()
        try {
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { bind(connection) }
                ?: return@withContext "Shizuku did not start the installer"
            val installer = IPrivilegedInstaller.Stub.asInterface(binder)
            ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { bytes ->
                installer.install(bytes, apk.length(), context.packageName)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku install failed: ${t.message}")
            t.message ?: t.javaClass.simpleName
        } finally {
            runCatching { Shizuku.unbindUserService(serviceArgs(), connection, true) }
        }
    }

    private suspend fun bind(connection: Connection): IBinder? =
        suspendCancellableCoroutine { waiting ->
            connection.onBound = { binder -> waiting.resume(binder) }
            runCatching { Shizuku.bindUserService(serviceArgs(), connection) }
                .onFailure { waiting.resume(null) }
        }

    /**
     * The version is part of the identity: Shizuku keeps a running service across app updates
     * unless it is told the code changed, and a stale one would be the previous release's.
     */
    private fun serviceArgs() = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedInstallerService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("installer")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private class Connection : ServiceConnection {
        var onBound: ((IBinder?) -> Unit)? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            onBound?.invoke(binder.takeIf { it?.pingBinder() == true })
            onBound = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onBound?.invoke(null)
            onBound = null
        }
    }

    private companion object {
        const val TAG = "ShizukuInstall"
        const val REQUEST_CODE = 4321
        const val BIND_TIMEOUT_MS = 10_000L

        /** Shizuku proper, and Sui, which is the same API built into a patched system. */
        val PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.redirect")
    }
}
