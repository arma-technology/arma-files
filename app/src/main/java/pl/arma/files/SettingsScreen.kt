package pl.arma.files

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    sources: List<StorageSource>,
    ftpConfig: FtpConfig,
    smbConfig: SmbConfig,
    allFilesAccess: Boolean,
    onGrantAllFilesAccess: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onEnabled: (SourceType, Boolean) -> Unit,
    onConnectCloud: (SourceType) -> Unit,
    onSaveFtp: (FtpConfig) -> Unit,
    onTestFtp: () -> Unit,
    onSaveSmb: (SmbConfig) -> Unit,
    onTestSmb: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsHero() }
        item {
            Text(
                "Źródła plików",
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
            FtpSettingsCard(
                config = ftpConfig,
                onEnabled = { onEnabled(SourceType.FTP, it) },
                onSave = onSaveFtp,
                onTest = onTestFtp
            )
        }

        item {
            SmbSettingsCard(
                config = smbConfig,
                onEnabled = { onEnabled(SourceType.SMB, it) },
                onSave = onSaveSmb,
                onTest = onTestSmb
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
                        Text("Dostęp i prywatność", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Źródła chmurowe korzystają z systemowego dostępu Androida. Hasła FTP/SMB są szyfrowane lokalnie kluczem przechowywanym w Android Keystore i nie są wysyłane do usług pośrednich.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                Text("Źródła i połączenia", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text("Włączaj tylko te miejsca, których używasz", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f))
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
                    Text("Pamięć urządzenia", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (allFilesAccess) "Pełny dostęp aktywny" else "Tryb ograniczony / SAF",
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
                "Pełny dostęp pozwala używać ekranu „Mój komputer” z pamięcią wbudowaną i kartą SD. SAF pozostaje alternatywą do podpinania pojedynczych folderów.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!allFilesAccess) {
                    Button(onClick = onGrantAllFilesAccess, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AdminPanelSettings, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Pełny dostęp")
                    }
                }
                FilledTonalButton(onClick = onPickLocalFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (safConfigured) "Zmień folder SAF" else "Folder SAF")
                }
            }
        }
    }
}

@Composable
private fun StaticSourceCard(icon: ImageVector, title: String, subtitle: String) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
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
                        if (source.enabled) source.subtitle else "Wyłączone",
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
                            if (source.configured) {
                                "Podłączone: ${source.subtitle}"
                            } else {
                                "Wybierz folder tego dostawcy w systemowym oknie Androida. " +
                                    "Aby podpiąć cały dysk, zaznacz najwyższy poziom (np. „Mój dysk”) " +
                                    "i potwierdź, zamiast wchodzić do podfolderu."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (source.configured) "Zmień podłączony folder" else "Podłącz folder")
                    }
                }
            }
        }
    }
}

@Composable
private fun FtpSettingsCard(config: FtpConfig, onEnabled: (Boolean) -> Unit, onSave: (FtpConfig) -> Unit, onTest: () -> Unit) {
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var user by remember(config.username) { mutableStateOf(config.username) }
    var pass by remember(config.password) { mutableStateOf(config.password) }
    var root by remember(config.rootPath) { mutableStateOf(config.rootPath) }
    var ftps by remember(config.ftps) { mutableStateOf(config.ftps) }

    ExpandableNetworkCard(
        icon = Icons.Default.Language,
        title = "FTP / FTPS",
        subtitle = if (config.enabled && config.host.isNotBlank()) "${config.host}:${config.port}" else if (config.enabled) "Włączone • wymaga konfiguracji" else "Wyłączone",
        enabled = config.enabled,
        onEnabled = onEnabled
    ) {
        SettingField("Serwer", host, { host = it }, "np. 192.168.1.10")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(.38f)) { SettingField("Port", port, { port = it.filter(Char::isDigit).take(5) }, "21") }
            Box(Modifier.weight(.62f)) { SettingField("Katalog startowy", root, { root = it }, "/") }
        }
        SettingField("Użytkownik", user, { user = it }, "login")
        SettingField("Hasło", pass, { pass = it }, "hasło", password = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FTPS (TLS)", fontWeight = FontWeight.Medium)
                Text("Szyfrowane połączenie FTP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ftps, onCheckedChange = { ftps = it })
        }
        val currentConfig = {
            FtpConfig(true, host.trim(), port.toIntOrNull() ?: 21, user, pass, root.ifBlank { "/" }, ftps)
        }
        NetworkButtons(
            canSave = host.isNotBlank(),
            onSave = { onSave(currentConfig()) },
            onTest = {
                onSave(currentConfig())
                onTest()
            }
        )
    }
}

@Composable
private fun SmbSettingsCard(config: SmbConfig, onEnabled: (Boolean) -> Unit, onSave: (SmbConfig) -> Unit, onTest: () -> Unit) {
    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var share by remember(config.share) { mutableStateOf(config.share) }
    var root by remember(config.rootPath) { mutableStateOf(config.rootPath) }
    var domain by remember(config.domain) { mutableStateOf(config.domain) }
    var user by remember(config.username) { mutableStateOf(config.username) }
    var pass by remember(config.password) { mutableStateOf(config.password) }

    ExpandableNetworkCard(
        icon = Icons.Default.Storage,
        title = "SMB / Samba",
        subtitle = if (config.enabled && config.host.isNotBlank()) "${config.host}/${config.share}" else if (config.enabled) "Włączone • wymaga konfiguracji" else "Wyłączone",
        enabled = config.enabled,
        onEnabled = onEnabled
    ) {
        SettingField("Serwer", host, { host = it }, "NAS, nazwa hosta lub IP")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(.35f)) { SettingField("Port", port, { port = it.filter(Char::isDigit).take(5) }, "445") }
            Box(Modifier.weight(.65f)) { SettingField("Udział", share, { share = it }, "np. Dokumenty") }
        }
        SettingField("Katalog startowy", root, { root = it }, "opcjonalnie")
        SettingField("Domena", domain, { domain = it }, "opcjonalnie")
        SettingField("Użytkownik", user, { user = it }, "login")
        SettingField("Hasło", pass, { pass = it }, "hasło", password = true)
        Text("Połączenie wymusza SMB2 lub nowszy protokół.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val currentConfig = {
            SmbConfig(true, host.trim(), port.toIntOrNull() ?: 445, share.trim(), root.trim(), domain.trim(), user, pass)
        }
        NetworkButtons(
            canSave = host.isNotBlank() && share.isNotBlank(),
            onSave = { onSave(currentConfig()) },
            onTest = {
                onSave(currentConfig())
                onTest()
            }
        )
    }
}

@Composable
private fun ExpandableNetworkCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabled)
            }
            AnimatedVisibility(enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(2.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingField(label: String, value: String, onValue: (String) -> Unit, placeholder: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp)
    )
}

@Composable
private fun NetworkButtons(canSave: Boolean, onSave: () -> Unit, onTest: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = onTest, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.NetworkCheck, null)
            Spacer(Modifier.width(7.dp))
            Text("Test")
        }
        Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Save, null)
            Spacer(Modifier.width(7.dp))
            Text("Zapisz")
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
