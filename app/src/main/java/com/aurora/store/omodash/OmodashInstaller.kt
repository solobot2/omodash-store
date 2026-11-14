/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.store.AuroraApp
import com.aurora.store.R
import com.aurora.store.data.event.InstallerEvent
import com.aurora.store.data.installer.base.InstallerBase
import com.aurora.store.data.model.DownloadStatus
import com.aurora.store.data.room.download.Download
import com.aurora.store.data.room.download.DownloadDao
import com.aurora.store.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class OmodashInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao
) : InstallerBase(context) {
    override fun install(download: Download) {
        synchronized(AuroraApp.enqueuedInstalls) {
            if (!AuroraApp.enqueuedInstalls.add(download.packageName)) return
        }
        AuroraApp.scope.launch {
            try {
                dao.updateStatus(download.packageName, DownloadStatus.INSTALLING)
                withContext(Dispatchers.IO) { stage(download) }
            } catch (error: Exception) {
                removeFromInstallQueue(download.packageName)
                dao.updateStatus(download.packageName, DownloadStatus.FAILED)
                postError(download.packageName, error.message, null)
            }
        }
    }

    override fun removeFromInstallQueue(packageName: String) {
        synchronized(AuroraApp.enqueuedInstalls) { super.removeFromInstallQueue(packageName) }
    }

    private suspend fun stage(download: Download) {
        val host = checkNotNull(StoreBridge.host(context)) { context.getString(R.string.omodash_host_unavailable) }
        val orderedFiles = download.fileList.sortedBy { if (it.type == PlayFile.Type.BASE) 0 else 1 }
        check(download.sharedLibs.isEmpty() && orderedFiles.count { it.type == PlayFile.Type.BASE } == 1 &&
            orderedFiles.all { it.type in setOf(PlayFile.Type.BASE, PlayFile.Type.SPLIT) }) {
            context.getString(R.string.omodash_unsupported_package)
        }
        val result = Intent(context, OmodashInstallResult::class.java).apply {
            action = StoreBridge.ACTION_RESULT
            data = Uri.Builder().scheme("omodash-store").authority("install-result")
                .appendPath(download.packageName).appendPath(download.versionCode.toString()).build()
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, 0, result, flags)
        val files = ArrayList<ParcelFileDescriptor>()
        try {
            orderedFiles.forEach { file ->
                files.add(ParcelFileDescriptor.open(PathUtil.getLocalFile(context, file, download), ParcelFileDescriptor.MODE_READ_ONLY))
            }
            val message = Message.obtain(null, StoreBridge.INSTALL, StoreBridge.VERSION, 0).apply {
                data = Bundle().apply {
                    putString("package", download.packageName)
                    putParcelableArrayList("files", files)
                    putParcelable("result", callback)
                }
            }
            withContext(Dispatchers.Main.immediate) { withTimeout(12_000) { send(host, message) } }
        } catch (error: Exception) {
            callback.cancel()
            throw error
        } finally {
            files.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun send(host: ComponentName, message: Message) =
        suspendCancellableCoroutine { continuation ->
            var bound = false
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!continuation.isActive) return
                    val result = runCatching { Messenger(service).send(message) }
                    if (bound) { bound = false; context.unbindService(this) }
                    result.fold({ continuation.resume(Unit) }, continuation::resumeWithException)
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
                override fun onNullBinding(name: ComponentName) {
                    if (bound) { bound = false; context.unbindService(this) }
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(context.getString(R.string.omodash_host_unavailable)))
                }
            }
            bound = context.bindService(Intent().setComponent(host), connection, Context.BIND_AUTO_CREATE)
            continuation.invokeOnCancellation {
                context.mainExecutor.execute { if (bound) { bound = false; context.unbindService(connection) } }
            }
            if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException(context.getString(R.string.omodash_host_unavailable)))
        }
}

@AndroidEntryPoint
class OmodashInstallResult : BroadcastReceiver() {
    @Inject lateinit var dao: DownloadDao
    @Inject lateinit var installer: OmodashInstaller

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StoreBridge.ACTION_RESULT) return
        val packageName = intent.data?.pathSegments?.getOrNull(0) ?: return
        val version = intent.data?.pathSegments?.getOrNull(1)?.toLongOrNull() ?: return
        val status = intent.getIntExtra("status", PackageInstaller.STATUS_FAILURE)
        val pending = goAsync()
        AuroraApp.scope.launch {
            try {
                val download = runCatching { dao.getDownload(packageName) }.getOrNull() ?: return@launch
                if (download.versionCode != version) return@launch
                installer.removeFromInstallQueue(packageName)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    dao.updateStatus(packageName, DownloadStatus.INSTALLED)
                    InstallerBase.notifyInstallation(context, download.displayName, packageName)
                    AuroraApp.events.send(InstallerEvent.Installed(packageName))
                } else {
                    dao.updateStatus(packageName, DownloadStatus.FAILED)
                    installer.postError(packageName, InstallerBase.getErrorString(context, status), intent.getStringExtra("message"))
                }
            } finally { pending.finish() }
        }
    }
}
