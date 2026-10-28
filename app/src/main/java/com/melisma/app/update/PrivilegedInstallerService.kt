package com.melisma.app.update

import android.os.ParcelFileDescriptor
import kotlin.system.exitProcess

/**
 * Installs an update from a process Shizuku started.
 *
 * Shizuku's own privilege is what makes this possible: the user granted it an ADB-level
 * identity, and a service it starts inherits that, so `pm` accepts an install from it the same
 * way it accepts one typed at a terminal. Nothing here is privileged on its own — without
 * Shizuku running and permission granted, the app never reaches this class.
 *
 * The APK arrives as a file descriptor and is piped to `pm install -S`, because this process
 * cannot open the app's private cache directory by path.
 */
class PrivilegedInstallerService : IPrivilegedInstaller.Stub() {

    override fun install(apk: ParcelFileDescriptor?, size: Long, installer: String?): String? {
        if (apk == null || size <= 0L) return "nothing to install"
        return try {
            val command = buildList {
                add("pm")
                add("install")
                add("-r")
                if (!installer.isNullOrBlank()) {
                    // Records who installed it, which is what decides whether Android asks
                    // before a later update.
                    add("-i")
                    add(installer)
                }
                add("-S")
                add(size.toString())
            }
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            ParcelFileDescriptor.AutoCloseInputStream(apk).use { bytes ->
                process.outputStream.use { bytes.copyTo(it, BUFFER) }
            }
            val said = process.inputStream.bufferedReader().use { it.readText() }
            val complained = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            when {
                code == 0 && said.contains("Success") -> null
                else -> complained.ifBlank { said }.trim().ifBlank { "pm exited with $code" }
            }
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * Shizuku does not stop a user service's process for us, so this ends it.
     */
    override fun destroy() = exitProcess(0)

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
