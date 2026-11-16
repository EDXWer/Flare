package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dimension.flare.R
import dev.dimension.flare.data.datastore.model.AiRequestHeader
import dev.dimension.flare.data.datastore.model.areValidAiRequestHeaders

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AiConfigHeadersSetting(
    headers: List<AiRequestHeader>,
    shapes: ListItemShapes,
    onSave: (List<AiRequestHeader>) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_ai_config_custom_headers)
    SegmentedListItem(
        checked = editing,
        onCheckedChange = { editing = it },
        shapes = shapes,
        content = { Text(title) },
        supportingContent = {
            Text(
                headers.joinToString { it.name }.ifEmpty { stringResource(R.string.settings_ai_config_value_empty_placeholder) },
                maxLines = 2,
            )
        },
    )
    if (editing) {
        var draft by remember { mutableStateOf(headers) }
        val valid = areValidAiRequestHeaders(draft)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    draft.forEachIndexed { index, header ->
                        OutlinedTextField(
                            value = header.name,
                            onValueChange = { name -> draft = draft.toMutableList().apply { set(index, header.copy(name = name)) } },
                            label = { Text(stringResource(R.string.settings_ai_config_header_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = header.value,
                            onValueChange = { value -> draft = draft.toMutableList().apply { set(index, header.copy(value = value)) } },
                            label = { Text(stringResource(R.string.settings_ai_config_header_value)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = { draft = draft.toMutableList().apply { removeAt(index) } },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(stringResource(R.string.delete)) }
                    }
                    if (!valid) {
                        Text(stringResource(R.string.settings_ai_config_headers_invalid), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { draft = draft + AiRequestHeader("", "") }) {
                        Text(stringResource(R.string.settings_ai_config_header_add))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    onSave(draft)
                    editing = false
                }) { Text(stringResource(R.string.image_translation_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
