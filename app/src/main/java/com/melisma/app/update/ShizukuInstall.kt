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
import java.util.concurrent.atomic.AtomicBoolean
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
        // `pm install` takes whatever package the file declares, where the PackageInstaller
        // session is told ours and refuses anything else. So the same limit is imposed here,
        // before Shizuku is involved at all: this installs Melisma or nothing. Android's
        // signature check then applies as it does to any replacement of an installed app.
        val declared = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName
        }.getOrNull()
        if (declared != context.packageName) {
            return@withContext "the download is not Melisma — it declares ${declared ?: "nothing"}"
        }
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
            waiting.invokeOnCancellation { connection.give(null) }
            runCatching { Shizuku.bindUserService(serviceArgs(), connection) }
                // Shizuku registered the connection before this failed, and keeps it; the answer
                // below is the only one it will ever give, and a late callback finds it spent.
                .onFailure { connection.give(null) }
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

    /**
      * Answers the bind exactly once.
      *
      * Shizuku holds on to a connection it has registered, including one whose bind call then
      * failed, so the same object can be called back after the attempt it belonged to is over.
      * Resuming a continuation twice is a crash, so the first answer takes it and the rest are
      * dropped.
      */
    internal class Connection : ServiceConnection {
        private val answered = AtomicBoolean(false)

        @Volatile
        var onBound: ((IBinder?) -> Unit)? = null

        /**
         * Winning the claim comes first. Reading the callback before claiming would let two
         * threads take one answer each — one the callback, the other the claim — and the bind
         * would then wait out its timeout with the answer already in hand.
         */
        fun give(binder: IBinder?) {
            if (!answered.compareAndSet(false, true)) return
            val waiting = onBound
            onBound = null
            waiting?.invoke(binder)
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) =
            give(binder.takeIf { it?.pingBinder() == true })

        override fun onServiceDisconnected(name: ComponentName?) = give(null)
    }

    private companion object {
        const val TAG = "ShizukuInstall"
        const val REQUEST_CODE = 4321
        const val BIND_TIMEOUT_MS = 10_000L

        /** Shizuku proper, and Sui, which is the same API built into a patched system. */
        val PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.redirect")
    }
}
