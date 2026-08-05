package com.cwenhe.timefence.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cwenhe.timefence.apps.InstalledApp

/** 搜索并选择需要限制的桌面应用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visibleApps = remember(apps, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) apps else apps.filter { app ->
            app.label.contains(normalized, ignoreCase = true) ||
                app.packageName.contains(normalized, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("选择应用 (${selectedPackages.size})") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回规则")
                }
            },
            actions = {
                TextButton(onClick = onDone, enabled = selectedPackages.isNotEmpty()) {
                    Text("完成")
                }
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("搜索应用") },
            singleLine = true,
        )
        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visibleApps, key = InstalledApp::packageName) { app ->
                    AppPickerRow(
                        app = app,
                        selected = app.packageName in selectedPackages,
                        onClick = { onToggle(app.packageName) },
                    )
                }
            }
        }
    }
}

/** 展示应用图标、名称、包名和选择框。 */
@Composable
private fun AppPickerRow(app: InstalledApp, selected: Boolean, onClick: () -> Unit) {
    val icon = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Checkbox(checked = selected, onCheckedChange = null)
    }
}
