package com.melisma.app.update;

import android.os.ParcelFileDescriptor;

/**
 * The installer that runs outside the app, with the privileges Shizuku was granted.
 *
 * Shizuku starts this in a process of its own; the app holds the only handle to it.
 */
interface IPrivilegedInstaller {

    /**
     * Install the APK behind [apk], which is read rather than opened by path: the download
     * lives in the app's private storage, which this process cannot reach.
     *
     * @param installer the package to record as having installed it.
     * @return null when the install succeeded, otherwise what went wrong.
     */
    String install(in ParcelFileDescriptor apk, long size, String installer) = 1;

    /** Shizuku's own transaction for shutting a user service down. */
    void destroy() = 16777114;
}
