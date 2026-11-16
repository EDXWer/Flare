package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dimension.flare.Res
import dev.dimension.flare.cancel
import dev.dimension.flare.data.datastore.model.AiRequestHeader
import dev.dimension.flare.data.datastore.model.areValidAiRequestHeaders
import dev.dimension.flare.delete
import dev.dimension.flare.edit
import dev.dimension.flare.image_translation_save
import dev.dimension.flare.settings_ai_config_custom_headers
import dev.dimension.flare.settings_ai_config_header_add
import dev.dimension.flare.settings_ai_config_header_name
import dev.dimension.flare.settings_ai_config_header_value
import dev.dimension.flare.settings_ai_config_headers_invalid
import dev.dimension.flare.settings_ai_config_value_empty_placeholder
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.ExpanderItem
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFluentApi::class)
@Composable
internal fun AiConfigHeadersSetting(
    headers: List<AiRequestHeader>,
    onSave: (List<AiRequestHeader>) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.settings_ai_config_custom_headers)
    ExpanderItem(
        heading = { Text(title) },
        caption = {
            Text(
                headers.joinToString { it.name }.ifEmpty { stringResource(Res.string.settings_ai_config_value_empty_placeholder) },
                maxLines = 2,
            )
        },
        trailing = {
            Button(modifier = Modifier.semantics { contentDescription = title }, onClick = { editing = true }) {
                Text(stringResource(Res.string.edit))
            }
        },
    )
    if (editing) {
        var drafts by remember { mutableStateOf(headers.map(::HeaderDraft)) }
        val current = drafts.map { AiRequestHeader(it.name.text.toString(), it.value.text.toString()) }
        val valid = areValidAiRequestHeaders(current)
        ContentDialog(
            title = title,
            visible = true,
            content = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    drafts.forEach { draft ->
                        key(draft) {
                            val name = stringResource(Res.string.settings_ai_config_header_name)
                            val value = stringResource(Res.string.settings_ai_config_header_value)
                            Text(name)
                            TextField(
                                state = draft.name,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = name },
                            )
                            Text(value)
                            TextField(
                                state = draft.value,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = value },
                            )
                            Button(modifier = Modifier.align(Alignment.End), onClick = { drafts = drafts - draft }) {
                                Text(stringResource(Res.string.delete))
                            }
                        }
                    }
                    if (!valid) Text(stringResource(Res.string.settings_ai_config_headers_invalid))
                    Button(onClick = { drafts = drafts + HeaderDraft(AiRequestHeader("", "")) }) {
                        Text(stringResource(Res.string.settings_ai_config_header_add))
                    }
                }
            },
            primaryButtonText = stringResource(Res.string.image_translation_save),
            closeButtonText = stringResource(Res.string.cancel),
            onButtonClick = {
                if (it == ContentDialogButton.Primary) {
                    if (!valid) return@ContentDialog
                    onSave(current)
                }
                editing = false
            },
        )
    }
}

private class HeaderDraft(
    header: AiRequestHeader,
) {
    val name = TextFieldState(header.name)
    val value = TextFieldState(header.value)
}
