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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
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
    onDisconnect: (StorageSource) -> Unit,
    onEditAccount: (RemoteAccount) -> Unit,
    onAccountEnabled: (RemoteAccount, Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onDiscover: () -> Unit,
    trashBytes: Long = 0L,
    onEmptyTrash: () -> Unit = {},
    indexProgress: pl.arma.files.index.IndexProgress = pl.arma.files.index.IndexProgress(),
    indexedFiles: Int = 0,
    onBuildIndex: () -> Unit = {},
    onCancelIndex: () -> Unit = {},
    onStorageChanged: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsHero() }
        item { AppearanceSettings() }
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
                onPickLocalFolder = onPickLocalFolder,
                onDisconnect = { onDisconnect(local) }
            )
        }

        listOf(SourceType.GOOGLE_DRIVE, SourceType.ONEDRIVE, SourceType.DROPBOX).forEach { type ->
            val source = sources.first { it.type == type }
            item(key = type.name) {
                CloudSourceCard(
                    source = source,
                    icon = sourceIcon(type),
                    onEnabled = { onEnabled(type, it) },
                    onConnect = { onConnectCloud(type) },
                    onDisconnect = { onDisconnect(source) }
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

        item { StorageCard(trashBytes, onEmptyTrash, indexProgress.running, onStorageChanged) }
        item { IndexCard(indexProgress, indexedFiles, onBuildIndex, onCancelIndex) }
        item { OfflineSettingsCard() }
        item { CacheCard() }
        item { LanguageCard() }
        item { AboutCard() }
    }
}

private val storageColors = mapOf(
    StoragePart.INDEX to Color(0xFF5C8DF6),
    StoragePart.DWG to Color(0xFFE08A3C),
    StoragePart.PREVIEWS to Color(0xFF4FB3A9),
    StoragePart.ARCHIVES to Color(0xFFB08A5A),
    StoragePart.OTHER_CACHE to Color(0xFF9E9E9E),
    StoragePart.OFFLINE to Color(0xFF7E6BD6),
    StoragePart.TRASH to Color(0xFFD45B5B),
    StoragePart.SETTINGS to Color(0xFF6D8C5A),
    StoragePart.APP to Color(0xFF3F6E8C),
    StoragePart.OTHER to Color(0xFF5E5E5E)
)

private fun storageLabel(part: StoragePart) = when (part) {
    StoragePart.INDEX -> R.string.storage_part_index to R.string.storage_part_index_desc
    StoragePart.DWG -> R.string.storage_part_dwg to R.string.storage_part_dwg_desc
    StoragePart.PREVIEWS -> R.string.storage_part_previews to R.string.storage_part_previews_desc
    StoragePart.ARCHIVES -> R.string.storage_part_archives to R.string.storage_part_archives_desc
    StoragePart.OTHER_CACHE -> R.string.storage_part_other_cache to R.string.storage_part_other_cache_desc
    StoragePart.OFFLINE -> R.string.storage_part_offline to R.string.storage_part_offline_desc
    StoragePart.TRASH -> R.string.storage_part_trash to R.string.storage_part_trash_desc
    StoragePart.SETTINGS -> R.string.storage_part_settings to R.string.storage_part_settings_desc
    StoragePart.APP -> R.string.storage_part_app to R.string.storage_part_app_desc
    StoragePart.OTHER -> R.string.storage_part_other to R.string.storage_part_other_desc
}

/**
 * Ile miejsca zajmuje aplikacja i na co: każda część osobno, z przyciskiem
 * czyszczenia tam, gdzie da się bezpiecznie wyczyścić, oraz „Wyczyść wszystko”.
 */
@Composable
private fun StorageCard(trashBytes: Long, onEmptyTrash: () -> Unit, indexing: Boolean, onChanged: () -> Unit) {
    val app = LocalContext.current.armaApp
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<StoragePart?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, trashBytes, indexing) {
        report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StorageUsage.measure(app, trashBytes) }
    }
    fun clear(parts: List<StoragePart>) {
        busy = true
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                parts.filter { it != StoragePart.TRASH }.forEach { StorageUsage.clear(app, it) }
            }
            if (StoragePart.TRASH in parts) onEmptyTrash()
            onChanged()
            busy = false
            refresh++
        }
    }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.storage_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        report?.let { stringResource(R.string.storage_total, formatSize(it.total)) } ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            val current = report
            if (current != null && current.total > 0) {
                // Pasek proporcji — od razu widać, co zajmuje najwięcej.
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
                    StoragePart.entries.forEach { part ->
                        val share = current[part].toFloat() / current.total
                        if (share > 0.004f) Box(Modifier.weight(share).fillMaxHeight().background(storageColors.getValue(part)))
                    }
                }
                StoragePart.entries.forEach { part ->
                    val bytes = current[part]
                    val (label, desc) = storageLabel(part)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(storageColors.getValue(part)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(formatSize(bytes), style = MaterialTheme.typography.labelLarge)
                        if (part.clearable) {
                            TextButton(
                                enabled = bytes > 0 && !busy && !(part == StoragePart.INDEX && indexing),
                                onClick = {
                                    // Indeks, kopie offline i kosz trudno odtworzyć — pytamy.
                                    if (part in setOf(StoragePart.INDEX, StoragePart.OFFLINE, StoragePart.TRASH)) confirm = part
                                    else clear(listOf(part))
                                }
                            ) { Text(stringResource(R.string.storage_clear)) }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
                OutlinedButton(
                    onClick = { confirmAll = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.storage_clear_all))
                }
            }
        }
    }

    confirm?.let { part ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(storageLabel(part).first)) },
            text = {
                Text(
                    stringResource(
                        when (part) {
                            StoragePart.INDEX -> R.string.storage_confirm_index
                            StoragePart.OFFLINE -> R.string.storage_confirm_offline
                            else -> R.string.storage_confirm_trash
                        }
                    )
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; clear(listOf(part)) }) { Text(stringResource(R.string.storage_clear)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (confirmAll) {
        var withTrash by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.storage_clear_all)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.storage_confirm_all))
                    Row(Modifier.clickable { withTrash = !withTrash }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = withTrash, onCheckedChange = { withTrash = it })
                        Text(stringResource(R.string.storage_confirm_all_trash))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    clear(StoragePart.entries.filter { it.clearable && (withTrash || it != StoragePart.TRASH) })
                }) { Text(stringResource(R.string.storage_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Indeks plików: stan, budowanie na żądanie i samoczynne odświeżanie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndexCard(
    progress: pl.arma.files.index.IndexProgress,
    indexedFiles: Int,
    onBuild: () -> Unit,
    onCancel: () -> Unit
) {
    val automation = LocalContext.current.armaApp.indexAutomation
    var enabled by remember { mutableStateOf(automation.enabled) }
    var onStartup by remember { mutableStateOf(automation.onStartup) }
    var days by remember { mutableIntStateOf(automation.everyDays) }
    var changeMb by remember { mutableIntStateOf(automation.onChangeMb) }
    val lastBuilt = remember(progress.running, progress.finishedAt) { automation.lastBuilt }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ManageSearch, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.index_card_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            progress.running -> stringResource(R.string.index_building, progress.scanned)
                            indexedFiles == 0 -> stringResource(R.string.index_card_empty)
                            lastBuilt > 0 -> stringResource(
                                R.string.index_card_state, indexedFiles,
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastBuilt))
                            )
                            else -> stringResource(R.string.index_card_count, indexedFiles)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (progress.running) TextButton(onClick = onCancel) { Text(stringResource(R.string.index_stop)) }
                else TextButton(onClick = onBuild) { Text(stringResource(R.string.index_build_now)) }
            }
            Text(
                stringResource(R.string.index_card_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.index_auto), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it; automation.enabled = it })
            }
            if (enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.index_auto_startup), modifier = Modifier.weight(1f))
                    Switch(checked = onStartup, onCheckedChange = { onStartup = it; automation.onStartup = it })
                }
                Text(stringResource(R.string.index_auto_every), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 1, 3, 7, 14, 30).forEach { value ->
                        FilterChip(
                            selected = value == days,
                            onClick = { days = value; automation.everyDays = value },
                            label = { Text(if (value == 0) stringResource(R.string.index_auto_off) else pluralStringResource(R.plurals.cache_days, value, value)) }
                        )
                    }
                }
                Text(stringResource(R.string.index_auto_change), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 100, 500, 1000, 5000).forEach { value ->
                        FilterChip(
                            selected = value == changeMb,
                            onClick = { changeMb = value; automation.onChangeMb = value },
                            label = { Text(if (value == 0) stringResource(R.string.index_auto_off) else formatSize(value * 1_048_576L)) }
                        )
                    }
                }
            }
        }
    }
}

/** Kopie offline: miejsce, harmonogram synchronizacji i sieć. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineSettingsCard() {
    val store = LocalContext.current.armaApp.offline
    val scope = rememberCoroutineScope()
    var location by remember { mutableStateOf(store.location) }
    var interval by remember { mutableIntStateOf(store.intervalMinutes) }
    var wifiOnly by remember { mutableStateOf(store.wifiOnly) }
    var moving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.OfflinePin, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.offline_settings_title), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (moving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(R.string.offline_location), style = MaterialTheme.typography.labelLarge)
            listOf(
                OfflineStore.Location.APP to (R.string.offline_location_app to R.string.offline_location_app_hint),
                OfflineStore.Location.FOLDER to (R.string.offline_location_folder to R.string.offline_location_folder_hint)
            ).forEach { (option, texts) ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !moving && option != location) {
                        problem = null
                        moving = true
                        scope.launch {
                            runCatching { store.moveTo(option) }
                                .onSuccess { location = option }
                                .onFailure { problem = it.message }
                            moving = false
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = option == location, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(texts.first))
                        Text(
                            stringResource(texts.second),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.offline_interval), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 60, 360, 1440).forEach { minutes ->
                    FilterChip(
                        selected = minutes == interval,
                        onClick = { interval = minutes; store.intervalMinutes = minutes },
                        label = {
                            Text(
                                when {
                                    minutes == 0 -> stringResource(R.string.offline_interval_manual)
                                    minutes < 60 -> "$minutes min"
                                    else -> "${minutes / 60} h"
                                }
                            )
                        }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.offline_wifi_only), modifier = Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; store.wifiOnly = it })
            }
        }
    }
}

/** Limit pamięci podręcznej: rozmiar, wiek i czyszczenie na żądanie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheCard() {
    val janitor = LocalContext.current.armaApp.cacheJanitor
    var limitMb by remember { mutableIntStateOf(janitor.maxMegabytes) }
    var ageDays by remember { mutableIntStateOf(janitor.maxAgeDays) }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.cache_limit_title), fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.cache_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.cache_limit_size), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheJanitor.SIZE_OPTIONS_MB.forEach { mb ->
                    FilterChip(
                        selected = mb == limitMb,
                        onClick = { limitMb = mb; janitor.maxMegabytes = mb },
                        label = { Text(formatSize(mb * 1_048_576L)) }
                    )
                }
            }
            Text(stringResource(R.string.cache_limit_age), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CacheJanitor.AGE_OPTIONS_DAYS.forEach { days ->
                    FilterChip(
                        selected = days == ageDays,
                        onClick = { ageDays = days; janitor.maxAgeDays = days },
                        label = { Text(pluralStringResource(R.plurals.cache_days, days, days)) }
                    )
                }
            }
        }
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
    "SLF4J" to "MIT",
    "libarchive" to "BSD-2-Clause",
    "Apache Commons Compress" to "Apache 2.0",
    "zip4j" to "Apache 2.0",
    "XZ for Java" to "0BSD",
    "zstd-jni" to "BSD-2-Clause",
    "Brotli" to "MIT"
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
    onPickLocalFolder: () -> Unit,
    onDisconnect: () -> Unit
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
            if (safConfigured) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LinkOff, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.source_disconnect))
                }
            }
        }
    }
}

@Composable
private fun CloudSourceCard(source: StorageSource, icon: ImageVector, onEnabled: (Boolean) -> Unit, onConnect: () -> Unit, onDisconnect: () -> Unit) {
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
            // Wyłączona chmura nadal trzyma uprawnienie do folderu — da się je
            // oddać bez włączania jej z powrotem.
            if (!source.enabled && source.configured) {
                TextButton(onClick = onDisconnect) {
                    Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.source_disconnect))
                }
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
                    if (source.configured) {
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.LinkOff, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.source_disconnect))
                        }
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
