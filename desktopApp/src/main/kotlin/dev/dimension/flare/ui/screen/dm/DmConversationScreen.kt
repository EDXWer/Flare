package dev.dimension.flare.ui.screen.dm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowRightFromBracket
import compose.icons.fontawesomeicons.solid.CircleUser
import compose.icons.fontawesomeicons.solid.EllipsisVertical
import compose.icons.fontawesomeicons.solid.Image
import compose.icons.fontawesomeicons.solid.PaperPlane
import compose.icons.fontawesomeicons.solid.Xmark
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.Res
import dev.dimension.flare.common.FileItem
import dev.dimension.flare.common.MimeTypes
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.dm_add_image
import dev.dimension.flare.dm_conversation
import dev.dimension.flare.dm_image_attached
import dev.dimension.flare.dm_image_send_failed
import dev.dimension.flare.dm_leave
import dev.dimension.flare.dm_remove_image
import dev.dimension.flare.dm_send_placeholder
import dev.dimension.flare.dm_sending
import dev.dimension.flare.dm_to_profile
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.more
import dev.dimension.flare.send
import dev.dimension.flare.ui.common.items
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.FlareScrollBar
import dev.dimension.flare.ui.component.RichText
import dev.dimension.flare.ui.component.dm.DMItem
import dev.dimension.flare.ui.component.dm.DMLoadingItem
import dev.dimension.flare.ui.model.onError
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.presenter.dm.DMConversationPresenter
import dev.dimension.flare.ui.presenter.dm.DMConversationState
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.theme.LocalComposeWindow
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.MenuFlyoutContainer
import io.github.composefluent.component.MenuFlyoutItem
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.io.FilenameFilter

@Composable
fun DmConversationScreen(
    accountType: AccountType,
    roomKey: MicroBlogKey,
    onBack: () -> Unit,
    toProfile: (MicroBlogKey) -> Unit,
) {
    val state by producePresenter(
        key = "dm_conversation_${accountType}_$roomKey",
    ) {
        presenter(
            accountType = accountType,
            roomKey = roomKey,
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    val window = LocalComposeWindow.current
    val listState = rememberLazyListState()
    state.items.onSuccess {
        if (listState.firstVisibleItemIndex == 0) {
            LaunchedEffect(itemCount) {
                listState.scrollToItem(0)
            }
        }
    }

    Column {
        Row(
            modifier =
                Modifier
                    .background(FluentTheme.colors.background.card.default)
                    .fillMaxWidth()
                    .padding(start = 40.dp)
                    .padding(LocalWindowPadding.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.users
                .onSuccess {
                    if (it.size == 1) {
                        RichText(
                            text = it.first().name,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.dm_conversation),
                        )
                    }
                }.onError {
                    Text(it.message.toString())
                }
            Spacer(modifier = Modifier.weight(1f))
            MenuFlyoutContainer(
                flyout = {
                    state.users.onSuccess {
                        if (it.size == 1) {
                            MenuFlyoutItem(
                                text = {
                                    Text(
                                        text = stringResource(Res.string.dm_to_profile),
                                    )
                                },
                                icon = {
                                    FAIcon(
                                        imageVector = FontAwesomeIcons.Solid.CircleUser,
                                        contentDescription = stringResource(Res.string.dm_to_profile),
                                    )
                                },
                                onClick = {
                                    isFlyoutVisible = false
                                    toProfile.invoke(it.first().key)
                                },
                            )
                        }
                    }

                    if (state.canLeave) {
                        MenuFlyoutItem(
                            text = {
                                Text(
                                    text = stringResource(Res.string.dm_leave),
                                    color = FluentTheme.colors.system.critical,
                                )
                            },
                            icon = {
                                FAIcon(
                                    imageVector = FontAwesomeIcons.Solid.ArrowRightFromBracket,
                                    contentDescription = stringResource(Res.string.dm_leave),
                                    tint = FluentTheme.colors.system.critical,
                                )
                            },
                            onClick = {
                                isFlyoutVisible = false
                                state.leave()
                                onBack()
                            },
                        )
                    }
                },
            ) {
                SubtleButton(
                    onClick = { isFlyoutVisible = true },
                    iconOnly = true,
                ) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.EllipsisVertical,
                        contentDescription = stringResource(Res.string.more),
                    )
                }
            }
        }
        FlareScrollBar(
            state = listState,
            reverseLayout = true,
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp),
                modifier =
                    Modifier
                        .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                stickyHeader {
                    val image = state.selectedImage
                    val attachmentIcon = if (image == null) FontAwesomeIcons.Solid.Image else FontAwesomeIcons.Solid.Xmark
                    val attachmentLabel = if (image == null) Res.string.dm_add_image else Res.string.dm_remove_image
                    Column(Modifier.padding(8.dp)) {
                        if (image != null) {
                            Text(
                                text =
                                    when {
                                        state.isSendingImage -> stringResource(Res.string.dm_sending)
                                        state.imageSendFailed -> stringResource(Res.string.dm_image_send_failed)
                                        else -> image.name ?: stringResource(Res.string.dm_image_attached)
                                    },
                                modifier = Modifier.padding(bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color =
                                    if (state.imageSendFailed) {
                                        FluentTheme.colors.system.critical
                                    } else {
                                        FluentTheme.colors.text.text.primary
                                    },
                            )
                        }
                        TextField(
                            state = state.text,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            trailing = {
                                Row {
                                    if (state.canSendImage) {
                                        SubtleButton(
                                            onClick = {
                                                if (image == null) {
                                                    pickImage(window)?.let(state::selectImage)
                                                } else {
                                                    state.selectImage(null)
                                                }
                                            },
                                            disabled = state.isSendingImage,
                                            iconOnly = true,
                                        ) {
                                            FAIcon(
                                                imageVector = attachmentIcon,
                                                contentDescription = stringResource(attachmentLabel),
                                            )
                                        }
                                    }
                                    SubtleButton(
                                        onClick = { state.send() },
                                        disabled = !state.canSend,
                                        iconOnly = true,
                                    ) {
                                        FAIcon(
                                            FontAwesomeIcons.Solid.PaperPlane,
                                            contentDescription = stringResource(Res.string.send),
                                        )
                                    }
                                }
                            },
                            placeholder = { Text(stringResource(Res.string.dm_send_placeholder)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            onKeyboardAction = {
                                if (state.canSend) {
                                    state.send()
                                }
                            },
                        )
                    }
                }
                items(
                    state.items,
                    key = {
                        it.id
                    },
                    loadingContent = {
                        DMLoadingItem()
                    },
                    itemContent = { item ->
                        DMItem(
                            item = item,
                            onRetry = {
                                state.retry(item.key)
                            },
                            modifier =
                                Modifier
                                    .animateItem()
                                    .padding(
                                        horizontal = screenHorizontalPadding,
                                    ),
                            onUserClicked = {
                                toProfile.invoke(it.key)
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun pickImage(window: ComposeWindow?): FileItem? {
    val file =
        FileDialog(window)
            .apply {
                filenameFilter = FilenameFilter { _, name -> MimeTypes.fromFileName(name)?.startsWith("image/") == true }
                isVisible = true
            }.files
            .firstOrNull() ?: return null
    val mimeType = MimeTypes.fromFileName(file.name)?.takeIf { it.startsWith("image/") } ?: return null
    return FileItem(file, mimeType = mimeType)
}

@Composable
private fun presenter(
    accountType: AccountType,
    roomKey: MicroBlogKey,
) = run {
    val text = rememberTextFieldState()
    val state =
        remember(
            accountType,
            roomKey,
        ) {
            DMConversationPresenter(
                accountType = accountType,
                roomKey = roomKey,
            )
        }.invoke()

    object : DMConversationState by state {
        val text = text
        val canSend = !isSendingImage && (selectedImage != null || text.text.isNotEmpty())

        fun send() {
            if (selectedImage != null) {
                sendImage()
            } else {
                send(text.text.toString())
                text.clearText()
            }
        }
    }
}
