/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aurora.store.R
import com.aurora.store.data.model.DownloadStatus
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun EmbeddedStore(catalog: EmbeddedCatalog, theme: StateFlow<Bundle>, onGoogleLogin: () -> Unit) {
    val settings by theme.collectAsState()
    val state by catalog.state.collectAsState()
    val downloads by catalog.downloads.collectAsState()
    val background = Color(settings.getInt("background", 0xff121212.toInt()))
    val surface = Color(settings.getInt("surface", 0xff171717.toInt()))
    val text = Color(settings.getInt("text", 0xfff3f3f3.toInt()))
    val muted = Color(settings.getInt("muted", 0xffa8a8a8.toInt()))
    val accent = Color(settings.getInt("accent", 0xffb98d6f.toInt()))
    val outline = Color(settings.getInt("outline", 0xff303030.toInt()))
    val dark = !settings.getBoolean("day")
    val onAccent = if (dark) Color.Black else Color.White
    val palette = if (dark) darkColorScheme(primary = accent, onPrimary = onAccent, background = background,
        onBackground = text, surface = surface, onSurface = text, onSurfaceVariant = muted, outline = outline)
    else lightColorScheme(primary = accent, onPrimary = onAccent, background = background,
        onBackground = text, surface = surface, onSurface = text, onSurfaceVariant = muted, outline = outline)
    val defaultDensity = LocalDensity.current
    val density = Density(settings.getFloat("density", defaultDensity.density).coerceIn(.5f, 8f),
        settings.getFloat("fontScale", defaultDensity.fontScale).coerceIn(.5f, 4f))
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colorScheme = palette) {
            Surface(color = background.copy(alpha = 0.10f), contentColor = text, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                    if (!state.signedIn) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = catalog::login, enabled = !state.busy) {
                                    Text(stringResource(R.string.omodash_anonymous_sign_in))
                                }
                                Button(onClick = onGoogleLogin, enabled = !state.busy) {
                                    Text(stringResource(R.string.omodash_google_sign_in))
                                }
                            }
                        }
                        return@Column
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (state.detail != null || state.showingDownloads) {
                            TextButton(onClick = catalog::back) { Text(stringResource(R.string.action_back)) }
                        }
                        if (!state.showingDownloads) TextButton(onClick = catalog::showDownloads) {
                            Text(stringResource(R.string.action_filter_downloads))
                        }
                    }
                    val detail = state.detail
                    when {
                        state.showingDownloads -> LazyColumn {
                            items(downloads, key = { it.packageName }) { download ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(download.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(download.status.localized), color = muted)
                                    }
                                    if (download.isActive) {
                                        TextButton({ catalog.cancel(download.packageName) }) { Text(stringResource(R.string.action_cancel)) }
                                    } else if (download.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
                                        TextButton({ catalog.retry(download.packageName) }) { Text(stringResource(R.string.action_retry)) }
                                    }
                                }
                                HorizontalDivider(color = outline)
                            }
                        }
                        detail != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val app = detail.app
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AsyncImage(app.iconArtwork.url, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.displayName, style = MaterialTheme.typography.headlineSmall)
                                        Text(app.packageName, color = muted)
                                    }
                                    val download = downloads.firstOrNull { it.packageName == app.packageName }
                                    if (download != null && (download.isActive || download.status in setOf(DownloadStatus.COMPLETED,
                                            DownloadStatus.INSTALLING, DownloadStatus.INSTALLED))) {
                                        Text(stringResource(download.status.localized))
                                    } else if (detail.resolved && app.isFree) {
                                        if (app.versionCode > 0) {
                                            Button(onClick = { catalog.install(detail) }, enabled = !state.busy) {
                                                Text(stringResource(R.string.action_install))
                                            }
                                        } else {
                                            Text(stringResource(R.string.omodash_no_install_version), color = muted)
                                        }
                                    }
                                }
                            }
                            item { Text(app.shortDescription) }
                        }
                        state.query.isBlank() || state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (!state.busy && state.error == null) Text(stringResource(
                                if (state.query.isBlank()) R.string.omodash_search_prompt else R.string.omodash_no_results), color = muted)
                        }
                        else -> LazyColumn {
                            items(state.results, key = { it.packageName }) { result ->
                                Row(Modifier.fillMaxWidth().clickable { catalog.details(result) }
                                    .heightIn(min = 62.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AsyncImage(result.iconArtwork.url, null, Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)))
                                    Column {
                                        Text(result.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(result.shortDescription, maxLines = 1, overflow = TextOverflow.Ellipsis, color = muted)
                                    }
                                }
                                HorizontalDivider(color = outline)
                            }
                        }
                    }
                }
            }
        }
    }
}
