/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.aurora.store.omodash

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.aurora.store.R
import com.aurora.store.compose.navigation.Destination
import com.aurora.store.compose.theme.AuroraTheme
import com.aurora.store.compose.ui.accounts.GoogleLoginScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** A regular window keeps the keyboard and Google verification usable on Android 11. */
@AndroidEntryPoint
internal class StoreGoogleLoginActivity : FragmentActivity() {
    @Inject lateinit var catalog: EmbeddedCatalog
    private val error = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuroraTheme {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_back)) }
                    if (error.intValue == 0) {
                        GoogleLoginScreen(
                            onNavigateTo = { destination ->
                                if (destination is Destination.Main) {
                                    catalog.googleSignedIn()
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                } else error.intValue = R.string.failed_to_generate_session
                            },
                            onPageError = { error.intValue = R.string.omodash_google_sign_in_error },
                            requireNewSession = true
                        )
                    } else {
                        Text(stringResource(error.intValue), color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp))
                        TextButton(onClick = { error.intValue = 0 }) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
    }
}
