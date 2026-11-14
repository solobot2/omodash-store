/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aurora.extensions.TAG
import com.aurora.store.AuroraApp
import com.aurora.store.data.helper.DownloadHelper
import com.aurora.store.util.NotificationUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-queues a previously failed download/install when the user taps "Retry" on its
 * (grouped) failure notification.
 */
@AndroidEntryPoint
class DownloadRetryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadHelper: DownloadHelper

    override fun onReceive(context: Context, intent: Intent?) {
        val packageName = intent?.getStringExtra(DownloadHelper.PACKAGE_NAME) ?: ""

        if (packageName.isNotBlank()) {
            Log.d(TAG, "Received retry request for $packageName")
            val pending = goAsync()
            AuroraApp.scope.launch(Dispatchers.IO) {
                try {
                    downloadHelper.retryDownload(packageName)
                    NotificationUtil.clearAppNotification(context, packageName)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not retry $packageName (${error.javaClass.simpleName})")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
