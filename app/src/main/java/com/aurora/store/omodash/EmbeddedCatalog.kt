/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.content.Context
import android.util.Log
import com.aurora.Constants
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.web.WebSearchHelper
import com.aurora.gplayapi.network.IHttpClient
import com.aurora.store.AuroraApp
import com.aurora.store.R
import com.aurora.store.data.helper.DownloadHelper
import com.aurora.store.data.model.AccountType
import com.aurora.store.data.providers.AccountProvider
import com.aurora.store.data.providers.AuthProvider
import com.aurora.store.util.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Singleton
internal class EmbeddedCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthProvider,
    private val webSearch: WebSearchHelper,
    private val http: IHttpClient,
    private val downloadHelper: DownloadHelper
) {
    // GPlayApi compares App instances by package name. Track the transition from a search
    // result to Play's resolved details explicitly so StateFlow still delivers it.
    data class Detail(val app: App, val resolved: Boolean = false)

    data class State(
        val signedIn: Boolean = false,
        val busy: Boolean = false,
        val query: String = "",
        val results: List<App> = emptyList(),
        val detail: Detail? = null,
        val showingDownloads: Boolean = false,
        val error: Int? = null
    )

    val state = MutableStateFlow(State(signedIn = AccountProvider.isLoggedIn(context)))
    val downloads = downloadHelper.downloadsList
    private var request: Job? = null

    fun login() = run(R.string.failed_to_generate_session) {
        if (Preferences.getStringSet(context, Preferences.PREFERENCE_DISPENSER_URLS).isEmpty()) {
            Preferences.putStringSet(context, Preferences.PREFERENCE_DISPENSER_URLS, setOf(Constants.URL_DISPENSER))
        }
        val session = auth.buildAnonymousAuthData().getOrThrow()
        check(session.authToken.isNotBlank() && session.deviceConfigToken.isNotBlank())
        auth.saveAuthData(session)
        val tokenType = if (session.aasToken.isBlank()) AuthHelper.Token.AUTH else AuthHelper.Token.AAS
        AccountProvider.login(context, session.email, session.aasToken.ifBlank { session.authToken }, tokenType, AccountType.ANONYMOUS)
        auth.persistAccount(session, AccountType.ANONYMOUS, authViaMicroG = false)
        val previous = state.value.query
        ensureActive()
        if (previous.isBlank()) state.value = State(signedIn = true)
        else {
            state.value = State(signedIn = true, query = previous, busy = true)
            val results = findApps(previous)
            ensureActive()
            state.value = State(signedIn = true, query = previous, results = results)
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        if (!state.value.signedIn) {
            state.value = state.value.copy(query = query)
            return
        }
        state.value = State(signedIn = true, query = query)
        run(R.string.omodash_search_error) {
            val results = findApps(query)
            ensureActive()
            state.value = State(signedIn = true, query = query, results = results)
        }
    }

    fun googleSignedIn() {
        val previous = state.value.query
        state.value = State(signedIn = true)
        if (previous.isNotBlank()) search(previous)
    }

    private fun findApps(query: String): List<App> = webSearch.searchResults(query).streamClusters.values
        .flatMap { it.clusterAppList }.distinctBy { it.packageName }

    fun details(app: App) {
        state.value = state.value.copy(detail = Detail(app), showingDownloads = false)
        run(R.string.omodash_details_error) {
            val detail = AppDetailsHelper(requireNotNull(auth.authData)).using(http).getAppByPackageName(app.packageName)
            check(detail.packageName == app.packageName)
            ensureActive()
            state.value = state.value.copy(detail = Detail(detail, resolved = true))
        }
    }

    fun install(detail: Detail) {
        val app = detail.app
        if (state.value.detail !== detail || !detail.resolved || app.versionCode <= 0) return
        if (app.dependencies.dependentLibraries.isNotEmpty() || app.fileList.any {
                it.type == PlayFile.Type.OBB || it.type == PlayFile.Type.PATCH
            }) {
            state.value = state.value.copy(error = R.string.omodash_unsupported_package)
            return
        }
        run(R.string.download_failed) { downloadHelper.enqueueApp(app) }
    }

    fun retry(packageName: String) = run(R.string.download_failed) { downloadHelper.retryDownload(packageName) }
    fun cancel(packageName: String) = run(R.string.download_failed) { downloadHelper.cancelDownload(packageName) }

    fun showDownloads() {
        request?.cancel()
        state.value = state.value.copy(showingDownloads = true, busy = false, error = null)
    }

    fun back() {
        request?.cancel()
        state.value = state.value.copy(detail = if (state.value.showingDownloads) state.value.detail else null,
            showingDownloads = false, busy = false, error = null)
    }

    private fun run(errorText: Int, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        request?.cancel()
        state.value = state.value.copy(busy = true, error = null)
        request = AuroraApp.scope.launch(Dispatchers.IO) {
            try {
                block()
                ensureActive()
                state.value = state.value.copy(busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GooglePlayException.AuthException) {
                ensureActive()
                state.value = state.value.copy(busy = false, signedIn = false, error = R.string.failed_to_generate_session)
            } catch (error: Exception) {
                Log.w("OmodashStore", "Store request failed (${error.javaClass.simpleName})")
                ensureActive()
                val detail = if (errorText == R.string.failed_to_generate_session) {
                    listOf(R.string.access_denied_using_vpn, R.string.login_rate_limited, R.string.server_maintenance,
                        R.string.server_unreachable).firstOrNull { context.getString(it) == error.message }
                } else null
                state.value = state.value.copy(busy = false, error = detail ?: errorText)
            }
        }
    }
}
