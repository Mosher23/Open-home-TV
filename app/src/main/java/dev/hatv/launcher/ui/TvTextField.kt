package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hatv.launcher.R

/** Browsing settings never creates a text input session. Select explicitly opens the editor. */
@Composable
internal fun TvTextField(
    label: String,
    value: String,
    change: (String) -> Unit,
    secret: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    // Deliberately not saveable: a token draft must never enter an Activity state Bundle.
    var draft by remember { mutableStateOf("") }
    var restoreFocus by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.select_to_edit)
    fun close() {
        keyboard?.hide()
        draft = ""
        editing = false
        restoreFocus = true
    }
    LaunchedEffect(editing, restoreFocus) {
        if (!editing && restoreFocus) {
            focus.requestFocus()
            restoreFocus = false
        }
    }
    Tile(
        {
            draft = value
            editing = true
        },
        Modifier.fillMaxWidth()
            .height(64.dp)
            .focusRequester(focus)
            .testTag("setting-field:$label")
            .semantics { contentDescription = "$label. $hint" },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(label, size = 11, color = Muted)
                Label(
                    if (value.isBlank()) "—"
                    else if (secret) "•".repeat(value.length.coerceAtMost(12)) else value,
                    size = 14,
                )
            }
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = Muted)
        }
    }
    if (editing)
        Dialog(
            onDismissRequest = { close() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val editorFocus = remember { FocusRequester() }
            var placed by remember { mutableStateOf(false) }
            LaunchedEffect(placed) { if (placed) editorFocus.requestFocus() }
            fun apply() {
                change(draft)
                close()
            }
            Column(
                Modifier.fillMaxWidth(.8f)
                    .widthIn(max = 640.dp)
                    .background(Ink, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Label(label, size = 20, bold = true)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(label) },
                    singleLine = true,
                    visualTransformation =
                        if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    modifier =
                        Modifier.fillMaxWidth()
                            .focusRequester(editorFocus)
                            .testTag("setting-editor")
                            .onGloballyPositioned { placed = true },
                )
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Action(stringResource(R.string.cancel), { close() })
                    Action(stringResource(R.string.done), { apply() }, selected = true)
                }
            }
        }
}
