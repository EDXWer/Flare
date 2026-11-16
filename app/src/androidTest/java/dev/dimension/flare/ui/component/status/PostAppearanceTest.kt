package dev.dimension.flare.ui.component.status

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.data.model.TimelineDisplayMode
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiMedia
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.createSampleStatus
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.model.toUiImage
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import androidx.compose.ui.graphics.Color as ComposeColor

@OptIn(ExperimentalComposeUiApi::class)
class PostAppearanceTest {
    @get:Rule val compose = createComposeRule()

    companion object {
        private var ownsKoin = false
        private var formattingScope: CoroutineScope? = null
        private var mediaQueryEnabled = false

        @JvmStatic @BeforeClass
        fun setUpFormatting() {
            mediaQueryEnabled = ComposeUiFlags.isMediaQueryIntegrationEnabled
            ComposeUiFlags.isMediaQueryIntegrationEnabled = true
            if (GlobalContext.getOrNull() == null) {
                val scope = MainScope()
                formattingScope = scope
                startKoin {
                    modules(
                        module {
                            single<CoroutineScope> { scope }
                            single<PlatformFormatter> {
                                object : PlatformFormatter {
                                    override fun formatNumber(number: Long) = number.toString()

                                    override fun formatRelativeInstant(instant: Instant) = "5m"

                                    override fun formatFullInstant(instant: Instant) = "2026-11-15"

                                    override fun formatAbsoluteInstant(instant: Instant) = "2026-11-15"
                                }
                            }
                        },
                    )
                }
                ownsKoin = true
            }
        }

        @JvmStatic @AfterClass
        fun stopFormatting() {
            ComposeUiFlags.isMediaQueryIntegrationEnabled = mediaQueryEnabled
            if (ownsKoin) stopKoin()
            formattingScope?.cancel()
        }
    }

    @Test
    fun attachmentsAndActionsUseTheIosSpacingGrid() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "post-spacing-media.png")
        val asset = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        asset.eraseColor(Color.rgb(42, 91, 105))
        file.outputStream().use { asset.compress(Bitmap.CompressFormat.PNG, 100, it) }
        asset.recycle()
        val url = file.toURI().toString()
        val body = "友達作るための行動したことないから当然の帰結だけど"
        val quoteBody = "文字と画像の間隔をそろえる。\n引用の内側にも余白を残す。"
        val sample =
            createSampleStatus(
                createSampleUser().copy(
                    nameInternal = "茶わらび".toUiPlainText(),
                    handle = UiHandle(raw = "Sawarabi_2000", host = "x.com", displayOverride = "@Sawarabi_2000"),
                    avatar = null,
                ),
            )
        val post =
            sample.copy(
                platformId = "X",
                platformIcon = IconType.Material(UiIcon.X),
                content = UiTranslatableText(body.toUiPlainText()),
                images = persistentListOf(UiMedia.Image(url, url, "Spacing reference media", 180f, 320f, false)),
            )
        val quote =
            sample.copy(
                user =
                    sample.user?.copy(
                        nameInternal = "引用ユーザー".toUiPlainText(),
                        handle = UiHandle(raw = "quoted", host = "x.com", displayOverride = "@quoted"),
                    ),
                content = UiTranslatableText(quoteBody.toUiPlainText()),
                actions = persistentListOf(),
            )
        lateinit var density: Density
        lateinit var bodyStyle: TextStyle
        var translation by mutableStateOf(TranslationDisplayState.Translated)
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(pureColorMode = true),
                LocalTimelineAppearance provides TimelineAppearance(fullWidthPost = false, showPlatformLogo = true),
                LocalStatusBackgroundColor provides ComposeColor.Black,
            ) {
                FlareTheme(darkTheme = true, dynamicColor = false) {
                    density = LocalDensity.current
                    bodyStyle = MaterialTheme.typography.bodyLarge
                    Surface(color = ComposeColor.Black) {
                        Box(Modifier.width(402.dp).testTag("component-spacing")) {
                            CommonStatusComponent(
                                post.copy(translationDisplayState = translation),
                                quotes = persistentListOf(quote),
                                isClickable = false,
                                modifier = Modifier.padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 8.dp),
                                actionsModifier = Modifier.testTag("spacing-actions"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithContentDescription("Spacing reference media", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .any { it.boundsInRoot.height > 100 }
        }
        val bodyBounds = compose.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val media =
            compose
                .onAllNodesWithContentDescription("Spacing reference media", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .maxBy { it.boundsInRoot.height }
                .boundsInRoot
        val quoteText = compose.onNodeWithText(quoteBody, useUnmergedTree = true).fetchSemanticsNode()
        val quoteFrame =
            generateSequence(quoteText.layoutInfo.coordinates.parentLayoutCoordinates) { it.parentLayoutCoordinates }
                .map { it.boundsInRoot() }
                .first { it.width >= media.width - 0.1f }
        val actions = compose.onNodeWithTag("spacing-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(10f * density.density, media.top - bodyBounds.bottom, 1.1f)
        assertEquals(8f * density.density, quoteFrame.top - media.bottom, 1.1f)
        assertEquals(12f * density.density, quoteText.boundsInRoot.left - quoteFrame.left, 1.1f)
        assertEquals(12f * density.density, quoteFrame.bottom - quoteText.boundsInRoot.bottom, 1.1f)
        assertEquals(2f * density.density, actions.top - quoteFrame.bottom, 1.1f)
        assertEquals(bodyBounds.left - 4f * density.density, actions.left, 1.1f)
        for ((state, label) in listOf(
            TranslationDisplayState.Translating to "Translating…",
            TranslationDisplayState.Failed to "Translation failed",
            TranslationDisplayState.Translated to "Translated",
        )) {
            compose.runOnIdle { translation = state }
            for (description in listOf("Platform: X", label)) {
                val icons = compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()
                check(icons.isNotEmpty())
                icons.forEach { assertEquals(with(density) { bodyStyle.fontSize.toPx() }, it.boundsInRoot.height, 1.1f) }
            }
        }
        if (InstrumentationRegistry.getArguments().getString("capturePostAppearance") == "1") {
            val directory = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("post-appearance"))
            val bitmap = compose.onNodeWithTag("component-spacing").captureToImage().asAndroidBitmap()
            File(directory, "android-components.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(
                directory,
                "android-component-bounds.txt",
            ).writeText("density=${density.density}\nbody=$bodyBounds\nmedia=$media\nquote=$quoteFrame\nactions=$actions")
        }
    }

    @Test
    fun inlineConversationsHideReplyPromptsAndStandaloneRepliesKeepThem() {
        val ordinary = post("morgan", "Morgan Lee", "A quiet morning by the river.\nThe path is open again after the rain.")
        val reposterName = "サムライ忍者GREENTEA / 原田くくま🥷🇯🇵"
        val repostMessage =
            UiTimelineV2.Message(
                user =
                    checkNotNull(ordinary.user).copy(
                        nameInternal = reposterName.toUiPlainText(),
                    ),
                statusKey = ordinary.statusKey,
                icon = UiIcon.Retweet,
                type = UiTimelineV2.Message.Type.Localized(UiTimelineV2.Message.Type.Localized.MessageId.Repost),
                createdAt = ordinary.createdAt,
                clickEvent = ClickEvent.Noop,
                accountType = ordinary.accountType,
            )
        val quote = post("avery", "Avery", "The footbridge is a good place to start.").copy(replyToHandle = "@visible-quote")
        val parent = post("jordan", "Jordan", "We will take a walk there this afternoon.").copy(replyToHandle = "@hidden-origin")
        val child = post("riley", "Riley", "See you by the entrance.").copy(replyToHandle = "@hidden-parent")
        val standalone = post("casey", "Casey", "There is also a cafe near the entrance.").copy(replyToHandle = "@visible-standalone")
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(),
                LocalTimelineAppearance provides TimelineAppearance(fullWidthPost = false, showTranslateButton = false),
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            StatusItem(
                                UiTimelineV2.TimelinePostItem(
                                    post = ordinary,
                                    presentation =
                                        UiTimelineV2.PostPresentation(
                                            message = repostMessage,
                                            quotes = persistentListOf(quote),
                                        ),
                                ),
                            )
                            HorizontalDivider()
                            StatusItem(
                                UiTimelineV2.TimelinePostItem(
                                    post = child,
                                    presentation =
                                        UiTimelineV2.PostPresentation(
                                            inlineParents = persistentListOf(UiTimelineV2.TimelinePostItem(post = parent)),
                                        ),
                                ),
                            )
                            HorizontalDivider()
                            StatusItem(standalone)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("@hidden-origin", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("@hidden-parent", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("@visible-quote", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(reposterName, useUnmergedTree = true).assertIsDisplayed()
        val repostLabel = compose.onNodeWithText("reposted a post", useUnmergedTree = true).assertIsDisplayed()
        val repostLayouts = mutableListOf<TextLayoutResult>()
        repostLabel.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(repostLayouts) }
        assertEquals(1, repostLayouts.single().lineCount)
        assertFalse(repostLayouts.single().hasVisualOverflow)
        screenshot("post-appearance-android.png")
        compose
            .onNodeWithText("@visible-standalone", substring = true, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        screenshot("post-appearance-android-reply.png")
    }

    @Test
    fun darkPostCalibrationUsesExpandedRichTextLineSpacing() {
        val body =
            "《少女前线2》 × SSSS.GRIDMAN 联动\n" +
                "2027年予定\n" +
                "将2名女主角作为角色实装\n" +
                "漢字漢字漢字漢字漢字\n" +
                "文字间距与行距保持一致。\n" +
                "文字间距与行距保持一致。\n" +
                "English spacing 0123456789\n" +
                "English spacing 0123456789"
        val author = "K12s"
        val reposter = "凪風　27年のお仕事積極募集中"
        val avatarUrl = avatar.toURI().toString()
        val post =
            createSampleStatus(
                createSampleUser().copy(
                    nameInternal = author.toUiPlainText(),
                    handle = UiHandle("kzwolf123", "x.com", displayOverride = "@kzwolf123"),
                    platformId = "X",
                    avatar = avatarUrl.toUiImage(),
                ),
            ).copy(
                content = UiTranslatableText(body.toUiPlainText()),
                images = persistentListOf(UiMedia.Image(avatarUrl, avatarUrl, "Sample media", 180f, 320f, false)),
                createdAt = (Clock.System.now() - 5.minutes).toUi(),
            )
        val timestamp = post.createdAt.relative
        val item =
            UiTimelineV2.TimelinePostItem(
                post = post,
                presentation =
                    UiTimelineV2.PostPresentation(
                        message =
                            UiTimelineV2.Message(
                                user = checkNotNull(post.user).copy(nameInternal = reposter.toUiPlainText()),
                                statusKey = post.statusKey,
                                icon = UiIcon.Retweet,
                                type = UiTimelineV2.Message.Type.Localized(UiTimelineV2.Message.Type.Localized.MessageId.Repost),
                                createdAt = post.createdAt,
                                clickEvent = ClickEvent.Noop,
                                accountType = post.accountType,
                            ),
                    ),
            )
        lateinit var density: Density
        lateinit var themeBody: TextStyle
        var fullWidth by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(pureColorMode = true),
                LocalTimelineAppearance provides
                    TimelineAppearance(
                        fullWidthPost = fullWidth,
                        showPlatformLogo = false,
                        showTranslateButton = false,
                        timelineDisplayMode = TimelineDisplayMode.Plain,
                    ),
            ) {
                FlareTheme(darkTheme = true, dynamicColor = false) {
                    density = LocalDensity.current
                    themeBody = MaterialTheme.typography.bodyLarge
                    Surface(color = ComposeColor.Black) {
                        Box(Modifier.width(402.dp).testTag("post-calibration")) {
                            AdaptiveCard(respectTimelineMode = true, totalCount = 1) {
                                StatusItem(item)
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val texts =
            listOf(
                "body" to body,
                "author" to author,
                "handle" to "@kzwolf123",
                "repost" to reposter,
                "timestamp" to timestamp,
            ).map { (role, text) ->
                val node = compose.onNodeWithText(text, useUnmergedTree = true)
                val result = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                CalibrationText(role, node.fetchSemanticsNode().boundsInRoot, result.single())
            }
        val container = compose.onNodeWithTag("post-calibration")
        val bounds = container.fetchSemanticsNode().boundsInRoot
        assertEquals(402f * density.density, bounds.width, 1f)
        assertEquals(
            ComposeColor(0xFFD9D9D9),
            texts
                .first { it.role == "body" }
                .layout.layoutInput.style.color,
        )
        assertEquals(
            ComposeColor(0xFF71767B),
            texts
                .first { it.role == "handle" }
                .layout.layoutInput.style.color,
        )
        if (InstrumentationRegistry.getArguments().getString("capturePostAppearance") == "1") {
            val directory = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("post-appearance"))
            for ((wide, name) in listOf(false to "android-post-calibration.png", true to "android-post-full-width.png")) {
                compose.runOnIdle { fullWidth = wide }
                val bitmap = container.captureToImage().asAndroidBitmap()
                File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            File(directory, "android-post-calibration.json").writeText(postCalibrationJson(density, themeBody, bounds, timestamp, texts))
        }
    }

    @Test
    fun timelineRowsUseTheSameInsetsInPlainAndCardMode() {
        val body = post("alex", "Alex Rivera", "A quiet afternoon at the coast.")
        val mei = checkNotNull(post("mei", "Mei Chen", "").user)

        fun message(
            id: String,
            name: String,
            icon: UiIcon,
            text: String,
        ) = UiTimelineV2.Message(
            user = post(id, name, "").user,
            statusKey = body.statusKey,
            icon = icon,
            type = UiTimelineV2.Message.Type.Raw(text),
            createdAt = body.createdAt,
            clickEvent = ClickEvent.Noop,
            accountType = body.accountType,
        )
        val rows =
            listOf(
                UiTimelineV2.TimelinePostItem(
                    body,
                    UiTimelineV2.PostPresentation(message = message("sam", "Sam Lee", UiIcon.Retweet, "reposted")),
                ),
                UiTimelineV2.User(
                    message("jordan", "Jordan Kim", UiIcon.Follow, "followed you"),
                    mei,
                    body.createdAt,
                    body.statusKey,
                    accountType = body.accountType,
                ),
                message("flare", "Flare Team", UiIcon.Pin, "pinned a post"),
            )
        var mode by mutableStateOf(TimelineDisplayMode.Plain)
        lateinit var density: Density
        compose.setContent {
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(pureColorMode = true),
                LocalTimelineAppearance provides TimelineAppearance(timelineDisplayMode = mode, showPlatformLogo = false),
            ) {
                FlareTheme(darkTheme = true, dynamicColor = false) {
                    density = LocalDensity.current
                    Surface(color = MaterialTheme.colorScheme.background) {
                        LazyStatusVerticalStaggeredGrid(
                            modifier = Modifier.height(310.dp).testTag("timeline"),
                            columns = StaggeredGridCells.Fixed(1),
                        ) {
                            items(rows.size) { index ->
                                AdaptiveCard(Modifier.testTag("row-$index"), index, rows.size, respectTimelineMode = true) {
                                    StatusItem(rows[index])
                                }
                            }
                        }
                    }
                }
            }
        }
        for ((displayMode, inset) in listOf(TimelineDisplayMode.Plain to 0.dp, TimelineDisplayMode.Card to 8.dp)) {
            compose.runOnIdle { mode = displayMode }
            val bounds = compose.onNodeWithTag("timeline").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("row-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val avatar =
                compose
                    .onNodeWithContentDescription("Open profile for ${mei.handle.display}", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertEquals(bounds.left + with(density) { inset.toPx() }, card.left, 1f)
            assertEquals(card.left + with(density) { 10.dp.toPx() }, avatar.left, 1f)
            screenshot("android-${displayMode.name.lowercase()}.png", "timeline")
        }
    }

    private val avatar by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "post-appearance-avatar.png").apply {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(91, 128, 165))
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun post(
        id: String,
        name: String,
        text: String,
    ): UiTimelineV2.Post =
        createSampleStatus(
            createSampleUser().copy(
                nameInternal = name.toUiPlainText(),
                handle = UiHandle(raw = id, host = "example.org"),
                avatar = avatar.toURI().toString().toUiImage(),
                banner = null,
            ),
        ).copy(
            statusKey = MicroBlogKey(id, "example.org"),
            content = UiTranslatableText(text.toUiPlainText()),
            visibility = UiTimelineV2.Post.Visibility.Public,
        )

    private fun screenshot(
        name: String,
        tag: String? = null,
    ) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        if (InstrumentationRegistry.getArguments().getString("capturePostAppearance") != "1") return
        val directory = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("post-appearance"))
        val node = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
