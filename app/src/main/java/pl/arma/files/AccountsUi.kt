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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.stringResource
import pl.arma.files.net.DiscoveredHost
import pl.arma.files.net.RemoteAccount
import pl.arma.files.net.RemoteType

fun typeLabel(type: RemoteType): String = when (type) {
    RemoteType.FTP -> "FTP"
    RemoteType.SFTP -> "SFTP"
    RemoteType.SMB -> "SMB"
    RemoteType.WEBDAV -> "WebDAV"
}

/** Wynik „Testuj” w edytorze połączenia; błąd pokazywany jest na czerwono. */
data class AccountTestResult(val ok: Boolean, val message: String)

fun typeIcon(type: RemoteType): ImageVector = when (type) {
    RemoteType.FTP -> Icons.Default.Storage
    RemoteType.SFTP -> Icons.Default.Lock
    RemoteType.SMB -> Icons.Default.Dns
    RemoteType.WEBDAV -> Icons.Default.Cloud
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
            Text(stringResource(R.string.accounts_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.accounts_text),
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
                                stringResource(R.string.accounts_incomplete, typeLabel(account.type))
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
                    TextButton(onClick = { onEdit(account) }) { Text(stringResource(R.string.accounts_edit)) }
                }
                HorizontalDivider()
            }

            if (accounts.isEmpty()) {
                Text(
                    stringResource(R.string.accounts_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.accounts_add))
                }
                FilledTonalButton(onClick = onDiscover, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.accounts_discover))
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
    testResult: AccountTestResult?,
    testing: Boolean,
    onTest: (RemoteAccount) -> Unit,
    onSave: (RemoteAccount) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }

    // Bez stanu częściowego: po każdej zmianie treści (wynik testu, schowanie
    // klawiatury) arkusz wracał do połowy ekranu i przyciski chowały się pod nim.
    // Port jako tekst: pole musi dać się wyczyścić i wpisać od nowa; pusty
    // oznacza port domyślny protokołu.
    var portText by remember(initial.id) { mutableStateOf(draft.port.toString()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        // Przewijany i z odstępem na klawiaturę: bez tego dolne pola (hasło,
        // katalog startowy) zostawały pod klawiaturą i nie było ich widać
        // w trakcie pisania. Pole z kursorem samo podjeżdża nad klawiaturę.
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                if (isNew) stringResource(R.string.accounts_new) else stringResource(R.string.accounts_editing),
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
                            if (wasDefault) portText = draft.port.toString()
                        },
                        label = { Text(typeLabel(type)) }
                    )
                }
            }

            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                label = { Text(stringResource(R.string.accounts_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.host,
                // Adres nie ma spacji, a klawiatura tekstowa wstawia ją po kropce
                // („100. 76.35.24”) — stąd klawiatura adresów i filtr spacji.
                onValueChange = { draft = draft.copy(host = it.filterNot(Char::isWhitespace)) },
                label = { Text(stringResource(R.string.accounts_host)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit).take(5)
                    portText = digits
                    val port = digits.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: RemoteAccount.defaultPort(draft.type, draft.secure)
                    draft = draft.copy(port = port)
                },
                placeholder = { Text(RemoteAccount.defaultPort(draft.type, draft.secure).toString()) },
                label = { Text(stringResource(R.string.accounts_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            if (draft.type == RemoteType.SMB) {
                OutlinedTextField(
                    value = draft.share,
                    onValueChange = { draft = draft.copy(share = it) },
                    label = { Text(stringResource(R.string.accounts_share)) },
                    supportingText = { Text(stringResource(R.string.accounts_share_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = draft.domain,
                    onValueChange = { draft = draft.copy(domain = it) },
                    label = { Text(stringResource(R.string.accounts_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text(stringResource(R.string.accounts_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text(stringResource(R.string.accounts_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.rootPath,
                onValueChange = { draft = draft.copy(rootPath = it) },
                label = { Text(stringResource(R.string.accounts_root)) },
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
                            if (wasDefault) portText = draft.port.toString()
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (draft.type == RemoteType.FTP) stringResource(R.string.accounts_ftps) else "HTTPS",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (draft.type == RemoteType.SFTP) {
                Text(
                    stringResource(R.string.accounts_sftp_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            testResult?.let {
                Text(
                    it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
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
                    Text(stringResource(R.string.accounts_test))
                }
                Button(
                    onClick = { onSave(draft); onDismiss() },
                    enabled = draft.configured,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.common_save)) }
            }

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.accounts_delete))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.accounts_delete_title, initial.label.ifBlank { typeLabel(initial.type) })) },
            text = {
                Text(
                    stringResource(R.string.accounts_delete_text)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial.id)
                    onDismiss()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
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
            Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.discovery_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (scanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.discovery_searching), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (hosts.isEmpty()) {
                Text(
                    stringResource(R.string.discovery_none),
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
                        AssistChip(onClick = { onUse(host) }, label = { Text(stringResource(R.string.common_add)) })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
