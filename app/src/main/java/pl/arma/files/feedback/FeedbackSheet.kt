package pl.arma.files.feedback

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.arma.files.BuildConfig
import pl.arma.files.R

private sealed interface SendState {
    data object Editing : SendState
    data object Sending : SendState
    data class Done(val number: Int, val url: String) : SendState
    data class Failed(val result: FeedbackResult) : SendState
}

@Composable
fun rememberDeviceInfo(): DeviceInfo {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DeviceInfo(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            model = DeviceInfo.modelName(Build.MANUFACTURER, Build.MODEL),
            language = locale.toLanguageTag()
        )
    }
}

// Formularz zgłoszenia. Gdy w kompilacji jest adres serwera zgłoszeń
// (feedbackUrl w local.properties), wysyła bezpośrednio; bez niego — albo gdy
// serwer nie odpowiada — otwiera gotowe zgłoszenie na GitHubie.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val device = rememberDeviceInfo()
    val serverConfigured = BuildConfig.FEEDBACK_URL.isNotBlank()

    var typeName by rememberSaveable { mutableStateOf(FeedbackType.BUG.name) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var includeDevice by rememberSaveable { mutableStateOf(true) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf<SendState>(SendState.Editing) }

    val type = FeedbackType.valueOf(typeName)
    val report = FeedbackReport(type, title, description, email, if (includeDevice) device else null)

    fun openGithub() = uriHandler.openUri(report.githubNewIssueUrl(BuildConfig.SOURCE_URL))

    ModalBottomSheet(
        onDismissRequest = { if (state != SendState.Sending) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.feedback_heading), style = MaterialTheme.typography.titleMedium)

            val done = state as? SendState.Done
            if (done != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.feedback_sent, done.number), fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { uriHandler.openUri(done.url) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feedback_view))
                    }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feedback_close))
                    }
                }
                return@Column
            }

            val editable = state != SendState.Sending
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == FeedbackType.BUG,
                    onClick = { typeName = FeedbackType.BUG.name },
                    enabled = editable,
                    leadingIcon = { Icon(Icons.Default.BugReport, null, Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.feedback_type_bug)) }
                )
                FilterChip(
                    selected = type == FeedbackType.FEATURE,
                    onClick = { typeName = FeedbackType.FEATURE.name },
                    enabled = editable,
                    leadingIcon = { Icon(Icons.Default.Lightbulb, null, Modifier.size(18.dp)) },
                    label = { Text(stringResource(R.string.feedback_type_feature)) }
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(FeedbackReport.TITLE_MAX) },
                label = { Text(stringResource(R.string.feedback_field_title)) },
                singleLine = true,
                enabled = editable,
                isError = showErrors && !report.titleValid,
                supportingText = if (showErrors && !report.titleValid) {
                    { Text(stringResource(R.string.feedback_title_error)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(FeedbackReport.DESCRIPTION_MAX) },
                label = { Text(stringResource(R.string.feedback_field_description)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (type == FeedbackType.BUG) R.string.feedback_description_hint_bug
                            else R.string.feedback_description_hint_feature
                        )
                    )
                },
                minLines = 4,
                enabled = editable,
                isError = showErrors && !report.descriptionValid,
                supportingText = if (showErrors && !report.descriptionValid) {
                    { Text(stringResource(R.string.feedback_description_error)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )
            if (serverConfigured) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.filterNot(Char::isWhitespace).take(254) },
                    label = { Text(stringResource(R.string.feedback_field_email)) },
                    singleLine = true,
                    enabled = editable,
                    isError = showErrors && !report.emailValid,
                    supportingText = {
                        Text(
                            stringResource(
                                if (showErrors && !report.emailValid) R.string.feedback_email_error
                                else R.string.feedback_email_note
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = includeDevice, onCheckedChange = { includeDevice = it }, enabled = editable)
                Column(Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.feedback_include_device))
                    if (includeDevice) {
                        Text(
                            device.lines().joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                stringResource(if (serverConfigured) R.string.feedback_public_note else R.string.feedback_github_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val failed = state as? SendState.Failed
            if (failed != null) {
                Text(
                    stringResource(
                        when (failed.result) {
                            is FeedbackResult.RateLimited -> R.string.feedback_rate_limited
                            is FeedbackResult.Invalid -> R.string.feedback_invalid
                            else -> R.string.feedback_unavailable
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (serverConfigured && failed != null && failed.result !is FeedbackResult.Invalid) {
                    TextButton(onClick = { if (report.isValid) openGithub() else showErrors = true }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.feedback_open_github))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = editable,
                    onClick = {
                        if (!report.isValid) {
                            showErrors = true
                            return@Button
                        }
                        if (!serverConfigured) {
                            openGithub()
                            onDismiss()
                            return@Button
                        }
                        state = SendState.Sending
                        scope.launch {
                            val result = FeedbackClient(BuildConfig.FEEDBACK_URL, BuildConfig.VERSION_NAME).send(report)
                            state = if (result is FeedbackResult.Sent) SendState.Done(result.number, result.url)
                            else SendState.Failed(result)
                        }
                    }
                ) {
                    if (state == SendState.Sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.feedback_sending))
                    } else if (serverConfigured) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.feedback_send))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.feedback_open_github))
                    }
                }
            }
        }
    }
}
