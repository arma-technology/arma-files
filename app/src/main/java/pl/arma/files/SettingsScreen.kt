package pl.arma.files

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import pl.arma.files.net.RemoteAccount

@Composable
fun SettingsScreen(
    sources: List<StorageSource>,
    accounts: List<RemoteAccount>,
    allFilesAccess: Boolean,
    onGrantAllFilesAccess: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onEnabled: (SourceType, Boolean) -> Unit,
    onConnectCloud: (SourceType) -> Unit,
    onEditAccount: (RemoteAccount) -> Unit,
    onAccountEnabled: (RemoteAccount, Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onDiscover: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsHero() }
        item {
            Text(
                stringResource(R.string.settings_file_sources),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }

        val local = sources.first { it.type == SourceType.LOCAL }
        item {
            LocalAccessCard(
                safConfigured = local.configured,
                allFilesAccess = allFilesAccess,
                onGrantAllFilesAccess = onGrantAllFilesAccess,
                onPickLocalFolder = onPickLocalFolder
            )
        }

        listOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX).forEach { type ->
            val source = sources.first { it.type == type }
            item(key = type.name) {
                CloudSourceCard(
                    source = source,
                    icon = sourceIcon(type),
                    onEnabled = { onEnabled(type, it) },
                    onConnect = { onConnectCloud(type) }
                )
            }
        }

        item {
            AccountList(
                accounts = accounts,
                onEdit = onEditAccount,
                onEnabled = onAccountEnabled,
                onAdd = onAddAccount,
                onDiscover = onDiscover
            )
        }

        item {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_privacy_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.settings_privacy_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item { LanguageCard() }
        item { AboutCard() }
    }
}

/** Język aplikacji; nazwy języków zawsze w ich własnym brzmieniu. */
@Composable
private fun LanguageCard() {
    val activity = LocalActivity.current ?: return
    val current = remember { AppLanguage.current(activity) }
    var picking by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val currentLabel = AppLanguage.options.firstOrNull { current != null && current.startsWith(it.tag) }?.name ?: systemLabel
    Surface(
        onClick = { picking = true },
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language), fontWeight = FontWeight.SemiBold)
                Text(currentLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                LazyColumn {
                    val choices = listOf<AppLanguage.Option?>(null) + AppLanguage.options
                    items(choices.size) { index ->
                        val option = choices[index]
                        val selected = if (option == null) current == null else current?.startsWith(option.tag) == true
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picking = false
                                    if (!selected) AppLanguage.set(activity, option?.tag)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option?.name ?: systemLabel)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Biblioteki dołączone do aplikacji i ich licencje — wszystkie zgodne z GPL-3.0. */
private val bundledLibraries = listOf(
    "LibreDWG" to "GPL-3.0-or-later",
    "PdfBox-Android" to "Apache 2.0",
    "AndroidX, Jetpack Compose" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "Apache Commons Net" to "Apache 2.0",
    "SSHJ" to "Apache 2.0",
    "jcifs-ng" to "LGPL-2.1",
    "SLF4J" to "MIT"
)

@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    var showLibraries by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_about), fontWeight = FontWeight.SemiBold)
                    Text(
                        "ARMA Files ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                stringResource(R.string.settings_about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { uriHandler.openUri(BuildConfig.SOURCE_URL) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Code, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_source_code))
                }
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://www.gnu.org/licenses/gpl-3.0.html") },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.settings_license)) }
            }
            TextButton(onClick = { showLibraries = !showLibraries }) {
                Text(if (showLibraries) stringResource(R.string.settings_hide_libraries) else stringResource(R.string.settings_libraries))
            }
            AnimatedVisibility(showLibraries) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    bundledLibraries.forEach { (name, license) ->
                        Row {
                            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHero() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary.copy(alpha = .9f))
                ),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
            )
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .14f)) {
                Icon(Icons.Default.Hub, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.settings_hero_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(stringResource(R.string.settings_hero_subtitle), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f))
            }
        }
    }
}

@Composable
private fun LocalAccessCard(
    safConfigured: Boolean,
    allFilesAccess: Boolean,
    onGrantAllFilesAccess: () -> Unit,
    onPickLocalFolder: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(Icons.Default.Smartphone)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_device_storage), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (allFilesAccess) stringResource(R.string.settings_full_access_on) else stringResource(R.string.settings_limited_access),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (allFilesAccess) Icons.Default.CheckCircle else Icons.Default.Lock,
                    null,
                    tint = if (allFilesAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_full_access_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!allFilesAccess) {
                    Button(onClick = onGrantAllFilesAccess, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AdminPanelSettings, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_full_access))
                    }
                }
                FilledTonalButton(onClick = onPickLocalFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (safConfigured) stringResource(R.string.settings_change_saf) else stringResource(R.string.settings_saf_folder))
                }
            }
        }
    }
}

@Composable
private fun CloudSourceCard(source: StorageSource, icon: ImageVector, onEnabled: (Boolean) -> Unit, onConnect: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(icon)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (source.enabled) source.subtitle else stringResource(R.string.settings_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onEnabled)
            }
            AnimatedVisibility(source.enabled) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (source.configured) Icons.Default.Link else Icons.Default.LinkOff, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (source.configured) stringResource(R.string.settings_attached, source.subtitle) else stringResource(R.string.settings_attach_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (source.configured) stringResource(R.string.settings_change_attached) else stringResource(R.string.settings_attach_folder))
                    }
                }
            }
        }
    }
}



@Composable
private fun SourceIcon(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Icon(icon, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

fun sourceIcon(type: SourceType): ImageVector = when (type) {
    SourceType.LOCAL -> Icons.Default.Smartphone
    SourceType.GOOGLE_DRIVE -> Icons.Default.Cloud
    SourceType.ONEDRIVE -> Icons.Default.CloudQueue
    SourceType.DROPBOX -> Icons.Default.Inventory2
    SourceType.FTP -> Icons.Default.Language
    SourceType.SMB -> Icons.Default.Storage
}
