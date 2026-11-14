/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The two APKs authenticate each other before exchanging surfaces or package files. */
internal object StoreBridge {
    const val VERSION = 1
    const val OPEN = 1
    const val RESIZE = 2
    const val SEARCH = 3
    const val CLOSE = 4
    const val SURFACE = 5
    const val ERROR = 6
    const val INSTALL = 7
    const val ACTION_RESULT = "win.solobot.omodash.store.INSTALL_RESULT"
    private const val HOST = "win.solobot.omodash"
    private const val CERT = "810b14da1f68836b92039603d4c99c2122c10d65c6d467ea0c8b2b3b1e13002a"
    private const val HOST_PREFS = "omodash_host"

    fun remember(context: Context, host: ComponentName) {
        context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE).edit()
            .putString("package", host.packageName).apply()
    }

    fun host(context: Context, uid: Int? = null): ComponentName? {
        val certificate = CERT.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val remembered = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE).getString("package", null)
        // A debug host may be signed with the release key for local testing.
        for (pkg in listOf(HOST, "$HOST.debug", "$HOST.updatetest.debug")) {
            if (uid == null && remembered != pkg) continue
            val info = try { context.packageManager.getApplicationInfo(pkg, 0) }
            catch (_: PackageManager.NameNotFoundException) { continue }
            if ((uid == null || uid == info.uid) && context.packageManager.hasSigningCertificate(
                    pkg, certificate, PackageManager.CERT_INPUT_SHA256
                )) return ComponentName(pkg, "$HOST.store.StoreInstallService")
        }
        return null
    }
}
