package pl.arma.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.arma.files.net.DiscoveredHost
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteType

fun typeLabel(type: RemoteType): String = when (type) {
    RemoteType.FTP -> "FTP"
    RemoteType.SFTP -> "SFTP"
    RemoteType.SMB -> "SMB"
    RemoteType.WEBDAV -> "WebDAV"
    RemoteType.GDRIVE -> "Google Drive"
}

fun typeIcon(type: RemoteType): ImageVector = when (type) {
    RemoteType.FTP -> Icons.Default.Storage
    RemoteType.SFTP -> Icons.Default.Lock
    RemoteType.SMB -> Icons.Default.Dns
    RemoteType.WEBDAV -> Icons.Default.Cloud
    RemoteType.GDRIVE -> Icons.Default.Cloud
}

/** Lista połączeń sieciowych na ekranie ustawień. */
@Composable
fun AccountList(
    accounts: List<RemoteAccount>,
    onEdit: (RemoteAccount) -> Unit,
    onEnabled: (RemoteAccount, Boolean) -> Unit,
    onAdd: () -> Unit,
    onDiscover: () -> Unit
) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Połączenia sieciowe", fontWeight = FontWeight.SemiBold)
            Text(
                "FTP, SFTP, SMB i WebDAV. Połączeń tego samego typu może być " +
                    "kilka — osobno dom, firma i kopie zapasowe.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            accounts.forEach { account ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        typeIcon(account.type),
                        null,
                        Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.label.ifBlank { typeLabel(account.type) },
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (account.configured) {
                                "${typeLabel(account.type)} · ${account.summary}"
                            } else {
                                "${typeLabel(account.type)} · wymaga uzupełnienia"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = account.enabled,
                        onCheckedChange = { onEnabled(account, it) }
                    )
                    TextButton(onClick = { onEdit(account) }) { Text("Edytuj") }
                }
                HorizontalDivider()
            }

            if (accounts.isEmpty()) {
                Text(
                    "Brak połączeń.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                    Text("Dodaj połączenie")
                }
                FilledTonalButton(onClick = onDiscover, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Szukaj w sieci")
                }
            }
        }
    }
}

/** Formularz jednego połączenia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorSheet(
    initial: RemoteAccount,
    isNew: Boolean,
    testResult: String?,
    testing: Boolean,
    onTest: (RemoteAccount) -> Unit,
    onSave: (RemoteAccount) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    signInAvailable: Boolean = false,
    onGoogleSignIn: (RemoteAccount) -> Unit = {}
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (isNew) "Nowe połączenie" else "Edycja połączenia",
                style = MaterialTheme.typography.titleMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteType.entries.forEach { type ->
                    FilterChip(
                        selected = draft.type == type,
                        onClick = {
                            // Przy zmianie protokołu port trzeba przestawić, ale
                            // tylko wtedy, gdy użytkownik go nie nadpisał ręcznie.
                            val wasDefault = draft.port == RemoteAccount.defaultPort(draft.type, draft.secure)
                            draft = draft.copy(
                                type = type,
                                port = if (wasDefault) RemoteAccount.defaultPort(type, draft.secure) else draft.port
                            )
                        },
                        label = { Text(typeLabel(type)) }
                    )
                }
            }

            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                label = { Text("Nazwa (np. NAS Dom)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (draft.type == RemoteType.GDRIVE) {
                GoogleDriveSection(
                    account = draft,
                    signInAvailable = signInAvailable,
                    onSignIn = { onGoogleSignIn(draft) }
                )
            }

            if (draft.type != RemoteType.GDRIVE) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    label = { Text("Adres serwera") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (draft.type != RemoteType.GDRIVE) OutlinedTextField(
                value = draft.port.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { draft = draft.copy(port = it) } },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            if (draft.type == RemoteType.SMB) {
                OutlinedTextField(
                    value = draft.share,
                    onValueChange = { draft = draft.copy(share = it) },
                    label = { Text("Nazwa udziału") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.domain,
                    onValueChange = { draft = draft.copy(domain = it) },
                    label = { Text("Domena (opcjonalnie)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (draft.type != RemoteType.GDRIVE) OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text("Użytkownik") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (draft.type != RemoteType.GDRIVE) OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text("Hasło") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.rootPath,
                onValueChange = { draft = draft.copy(rootPath = it) },
                label = {
                    Text(
                        if (draft.type == RemoteType.GDRIVE) {
                            "Katalog startowy na dysku (opcjonalnie)"
                        } else {
                            "Katalog startowy (opcjonalnie)"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (draft.type == RemoteType.FTP || draft.type == RemoteType.WEBDAV) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = draft.secure,
                        onCheckedChange = { secure ->
                            val wasDefault = draft.port == RemoteAccount.defaultPort(draft.type, draft.secure)
                            draft = draft.copy(
                                secure = secure,
                                port = if (wasDefault) RemoteAccount.defaultPort(draft.type, secure) else draft.port
                            )
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (draft.type == RemoteType.FTP) "Szyfrowanie TLS (FTPS)" else "HTTPS",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (draft.type == RemoteType.SFTP) {
                Text(
                    "SFTP jest zawsze szyfrowany i działa przez jedno połączenie, " +
                        "więc nie wymaga trybu pasywnego ani otwierania portów.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onTest(draft) },
                    enabled = !testing && draft.configured,
                    modifier = Modifier.weight(1f)
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Testuj")
                }
                Button(
                    onClick = { onSave(draft); onDismiss() },
                    enabled = draft.configured,
                    modifier = Modifier.weight(1f)
                ) { Text("Zapisz") }
            }

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Usuń połączenie")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć ${initial.label.ifBlank { typeLabel(initial.type) }}?") },
            text = {
                Text(
                    "Zniknie z listy źródeł razem z zapisanym hasłem. Pliki na " +
                        "serwerze zostają nietknięte."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial.id)
                    onDismiss()
                }) { Text("Usuń") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } }
        )
    }
}

/**
 * Sekcja logowania do Google Drive. Adresu serwera i hasła tu nie ma —
 * dostępu udziela sam Google, a aplikacja dostaje token.
 */
@Composable
private fun GoogleDriveSection(
    account: RemoteAccount,
    signInAvailable: Boolean,
    onSignIn: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!signInAvailable) {
                Text(
                    "Ta wersja aplikacji nie ma wpisanego Client ID Google. " +
                        "Zbuduj ją z parametrem driveClientId, aby włączyć logowanie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }
            Text(
                if (account.configured) {
                    "Zalogowano jako ${account.username.ifBlank { "konto Google" }}"
                } else {
                    "Zaloguj się swoim kontem Google. Logowanie odbywa się w przeglądarce, " +
                        "więc hasło nie przechodzi przez aplikację."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Cloud, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (account.configured) "Zaloguj ponownie" else "Zaloguj się do Google")
            }
        }
    }
}

/** Wynik skanowania sieci lokalnej. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverySheet(
    scanning: Boolean,
    hosts: List<DiscoveredHost>,
    onUse: (DiscoveredHost) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Serwery w sieci lokalnej", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Sprawdzamy, które urządzenia w Twojej sieci mają otwarty port SMB (445).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Szukam…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (hosts.isEmpty()) {
                Text(
                    "Nie znaleziono serwerów. Sieci gościnne i Wi-Fi z izolacją klientów " +
                        "blokują taki ruch — wtedy adres trzeba wpisać ręcznie.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(hosts, key = { it.address }) { host ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.name ?: host.address, fontWeight = FontWeight.Medium)
                            if (host.name != null) {
                                Text(
                                    host.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        AssistChip(onClick = { onUse(host) }, label = { Text("Dodaj") })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
