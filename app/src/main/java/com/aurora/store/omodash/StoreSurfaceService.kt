/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.ContextThemeWrapper
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
internal class StoreSurfaceService : Service() {
    @Inject lateinit var catalog: EmbeddedCatalog

    private val handler = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val theme = MutableStateFlow(Bundle())
    private var session: Session? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun receive(message: Message): Boolean {
        val reply = message.replyTo ?: return true
        val caller = StoreBridge.host(this, message.sendingUid)
        if (message.arg1 != StoreBridge.VERSION || caller == null) {
            android.util.Log.w("OmodashStore", "Rejected store surface caller UID ${message.sendingUid}")
            return true
        }
        if (message.what == StoreBridge.OPEN) {
            try {
                open(message, reply)
                StoreBridge.remember(this, caller)
            } catch (error: Exception) {
                android.util.Log.w("OmodashStore", "Could not attach store surface", error)
                close()
                runCatching { reply.send(Message.obtain(null, StoreBridge.ERROR, StoreBridge.VERSION, message.arg2)) }
            }
            return true
        }
        val current = session ?: return true
        if (current.reply.binder != reply.binder || current.generation != message.arg2) return true
        when (message.what) {
            StoreBridge.RESIZE -> {
                val size = dimensions(message.data) ?: return true
                theme.value = Bundle(message.data)
                current.host.relayout(size.first, size.second)
            }
            StoreBridge.SEARCH -> catalog.search(message.data.getString("query").orEmpty().take(160))
            StoreBridge.CLOSE -> close()
        }
        return true
    }

    private fun open(message: Message, reply: Messenger) {
        close()
        val data = message.data
        val (width, height) = requireNotNull(dimensions(data))
        val token = requireNotNull(data.getBinder("token"))
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt("display"))
            ?: error("Display unavailable")
        val displayContext = createDisplayContext(display)
        val language = data.getString("locale").orEmpty()
        val localized = if (language.isBlank()) displayContext else displayContext.createConfigurationContext(
            Configuration(displayContext.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
        )
        val window = localized.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
        val owner = StoreViewOwner()
        val view = ComposeView(ContextThemeWrapper(window, com.aurora.store.R.style.AppTheme)).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { EmbeddedStore(catalog, theme) }
        }
        val host = SurfaceControlViewHost(window, display, token)
        val death = IBinder.DeathRecipient { handler.post { if (session?.reply?.binder == reply.binder) close() } }
        session = Session(host, view, owner, reply, message.arg2, death)
        reply.binder.linkToDeath(death, 0)
        theme.value = Bundle(data)
        host.setView(view, width, height)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val surface = requireNotNull(host.surfacePackage)
        try {
            reply.send(Message.obtain(null, StoreBridge.SURFACE, StoreBridge.VERSION, message.arg2).apply {
                this.data = Bundle().apply { putParcelable("surface", surface) }
            })
        } finally { surface.release() }
    }

    private fun dimensions(data: Bundle): Pair<Int, Int>? {
        val width = data.getInt("width")
        val height = data.getInt("height")
        return if (width in 1..8192 && height in 1..8192) width to height else null
    }

    private fun close() {
        val current = session ?: return
        session = null
        runCatching { current.reply.binder.unlinkToDeath(current.death, 0) }
        current.view.disposeComposition()
        current.owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        current.host.release()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return false
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private data class Session(
        val host: SurfaceControlViewHost,
        val view: ComposeView,
        val owner: StoreViewOwner,
        val reply: Messenger,
        val generation: Int,
        val death: IBinder.DeathRecipient
    )
}

private class StoreViewOwner : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null)
        lifecycle.currentState = Lifecycle.State.CREATED
    }
}
