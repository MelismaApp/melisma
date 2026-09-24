package com.melisma.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.melisma.app.MelismaApp

/**
 * Where Android reports on an update's install session.
 *
 * Declared in the manifest rather than registered at run time, so a status sent after the process
 * was reclaimed — Android wanting its confirmation shown, say — starts it again rather than being
 * dropped. Not exported: only the session's own PendingIntent reaches it.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as MelismaApp).container.updater
        updater.onInstallStatus(
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }
}
