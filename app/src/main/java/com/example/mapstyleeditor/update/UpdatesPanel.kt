package com.example.mapstyleeditor.update

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

/** The settings menu's Updates tab: this version, the latest one, and the automatic update switches. */
@Composable
fun UpdatesPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdates.state.collectAsState()
    var autoCheck by remember { mutableStateOf(AppUpdates.autoCheck(context)) }
    var autoInstall by remember { mutableStateOf(AppUpdates.autoInstall(context)) }
    // Re-read after coming back from Android's "Install unknown apps" screen.
    var canInstall by remember { mutableStateOf(AppUpdates.canInstall(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = AppUpdates.canInstall(context) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("MinMap ${AppUpdates.currentVersion(context)}", style = MaterialTheme.typography.titleMedium)
            val latest = state.latest
            val line = when {
                state.checking -> "Checking for updates…"
                state.available && latest != null -> "Version ${latest.version} is available"
                latest != null -> "You're up to date"
                else -> "Not checked yet"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium)
            if (state.checkedAt > 0 && !state.checking) {
                Text(
                    "Checked " + DateUtils.getRelativeTimeSpanString(state.checkedAt).toString().lowercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }

        val latest = state.latest
        if (state.available && latest != null) {
            if (latest.notes.isNotBlank()) {
                Text(
                    latest.notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
            }
            val progress = state.progress
            when {
                progress != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                state.installing -> Text(
                    "Installing. MinMap closes to finish the update; open it again afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                !canInstall -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Android needs your OK for MinMap to install updates (a one-time switch).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { AppUpdates.openInstallPermission(context) }) { Text("Allow installing updates") }
                }
                else -> Button(onClick = { scope.launch { AppUpdates.downloadAndInstall(context) } }) {
                    Text("Download and install (${latest.apkBytes / 1_000_000} MB)")
                }
            }
        } else {
            OutlinedButton(enabled = !state.checking, onClick = { scope.launch { AppUpdates.check(context) } }) {
                Text("Check now")
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        SwitchRow(
            title = "Check for updates automatically",
            detail = "Twice a day at most, when MinMap opens",
            checked = autoCheck,
        ) {
            autoCheck = it
            AppUpdates.setAutoCheck(context, it)
        }
        SwitchRow(
            title = "Install updates automatically",
            detail = "On Wi-Fi. Android asks you to confirm the first one; after that they install on their own.",
            checked = autoInstall,
            enabled = autoCheck,
        ) {
            autoInstall = it
            AppUpdates.setAutoInstall(context, it)
        }

        Text(
            "Updates come from MinMap's releases on GitHub (github.com/Tman600/MinMap). Each download " +
                "must match its published SHA-256 checksum and be signed with the same key as this app, " +
                "or it isn't installed.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
