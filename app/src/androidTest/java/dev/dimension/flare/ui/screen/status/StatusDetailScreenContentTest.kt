package dev.dimension.flare.ui.screen.status

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dimension.flare.common.collectPagingState
import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.model.BottomBarBehavior
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.component.resolveText
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.UiIcon
import dev.dimension.flare.ui.model.UiNumber
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.asText
import dev.dimension.flare.ui.model.asType
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.presenter.status.StatusContextPresenter
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Instant

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class StatusDetailScreenContentTest {
    @get:Rule val compose = createComposeRule()

    private val originalMediaQueryIntegration = ComposeUiFlags.isMediaQueryIntegrationEnabled
    private var renderedLabels = emptyMap<UiStrings, String>()

    init {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = true
    }

    @After
    fun restoreMediaQueryIntegration() {
        ComposeUiFlags.isMediaQueryIntegrationEnabled = originalMediaQueryIntegration
    }

    @Test
    fun openingDetailsKeepsThePostAtTheTopWhenTabsLoadFirst() {
        val postLoaded = mutableStateOf(false)
        val postText = "The canal path is open again this morning."
        val root = post("xQt", "x.com", "late-post", "Maya Patel", postText)
        show(
            root = root,
            primaryTab = UiStrings.Replies,
            secondaryTab = UiStrings.Quotes,
            filterOptions = listOf(UiStrings.Relevance),
            contents = mapOf(UiStrings.Relevance to page(root, "replies", "Thanks for checking.", "I’ll take that route.")),
            isPostLoaded = { postLoaded.value },
        )

        val post = compose.onNodeWithText(postText)
        post.assertDoesNotExist()
        compose.runOnIdle { postLoaded.value = true }

        post.assertIsDisplayed()
        val scrollPosition =
            compose
                .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
                .value()
        assertEquals(0f, scrollPosition, 0.001f)
        choice(UiStrings.Replies).assertIsSelected()
    }

    @Test
    fun weiboSortChangesCommentsWithoutScrollingAndRepostsHaveNoSort() {
        val root =
            post(
                platformId = "VVo",
                host = "m.weibo.cn",
                id = "weibo-root",
                name = "林晓雨",
                text = "周末去了一趟浦东图书馆。三楼靠窗的长桌很适合看书，上午人少。出门后沿着公园的小路走，还能赶上午后的阳光。",
            )
        val hot = "周六上午去过，人不多，窗边光线很好。"
        val time = "刚到图书馆，一楼还有空位。"
        val repost = "收藏这条路线，周末带家人一起去。"
        show(
            root = root,
            primaryTab = UiStrings.Comments,
            secondaryTab = UiStrings.Reposts,
            additionalTabs = listOf(UiStrings.LikedBy),
            filterOptions = listOf(UiStrings.ByPopularity, UiStrings.ByTime),
            contents =
                mapOf(
                    UiStrings.ByPopularity to page(root, "hot", hot, "谢谢分享，这家图书馆的儿童区也值得逛。"),
                    UiStrings.ByTime to page(root, "time", time, "今天正好路过，下午准备去看看。"),
                    UiStrings.Reposts to page(root, "reposts", repost, "离地铁站不远，适合安排半天。"),
                    UiStrings.LikedBy to people(root, "许宁", "叶青", "张曦"),
                ),
        )

        val comments = choice(UiStrings.Comments)
        comments.assertIsSelected()
        compose.onNodeWithText(hot).assertIsDisplayed()
        filter().performClick()
        menuOption(UiStrings.ByTime).assertIsDisplayed()
        screenshot("post-detail-weibo.png")
        menuOption(UiStrings.ByPopularity).performClick()

        val initialTop = comments.fetchSemanticsNode().boundsInRoot.top
        compose
            .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 84f) }
        compose.waitForIdle()
        val scrolledTop = comments.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The fixture must start the filter change from a scrolled position", scrolledTop < initialTop - 20f)

        filter().performClick()
        menuOption(UiStrings.ByTime).performClick()
        compose.onNodeWithText(time).assertIsDisplayed()
        compose.onNodeWithText(hot).assertDoesNotExist()
        assertEquals(scrolledTop, comments.fetchSemanticsNode().boundsInRoot.top, 2f)

        choice(UiStrings.Reposts).performClick().assertIsSelected()
        compose.onNodeWithText(repost).assertIsDisplayed()
        filter().assertDoesNotExist()
        assertEquals(scrolledTop, comments.fetchSemanticsNode().boundsInRoot.top, 2f)

        comments.performClick().assertIsSelected()
        compose.onNodeWithText(time).assertIsDisplayed()
        assertEquals(scrolledTop, comments.fetchSemanticsNode().boundsInRoot.top, 2f)

        compose
            .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -84f) }
        choice(UiStrings.LikedBy).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("许宁").assertIsDisplayed()
        filter().assertDoesNotExist()
        screenshot("post-detail-weibo-liked-by.png")
    }

    @Test
    fun xRepliesShowSortOptionsAndQuotesSwapTheActiveList() {
        val root =
            post(
                platformId = "xQt",
                host = "x.com",
                id = "x-root",
                name = "Maya Patel",
                text = "Took the canal loop before work. The west path was quiet, and the footbridge is open again.",
            )
        val relevant = "I walked it around eight and had the west path to myself."
        val latest = "Just passed the bridge. The ramp is open from both sides."
        val liked = "Bring a camera. The view from the footbridge is worth the walk."
        val quote = "Adding the canal loop to Saturday’s photo walk."
        show(
            root = root,
            primaryTab = UiStrings.Replies,
            secondaryTab = UiStrings.Quotes,
            additionalTabs = listOf(UiStrings.RepostedBy),
            filterOptions = listOf(UiStrings.Relevance, UiStrings.Recency, UiStrings.Likes),
            contents =
                mapOf(
                    UiStrings.Relevance to page(root, "relevant", relevant, "There’s a nice bakery past the north end, too."),
                    UiStrings.Recency to page(root, "latest", latest, "Thanks for the update. This saves me the long route home."),
                    UiStrings.Likes to page(root, "liked", liked, "The reeds by the lock get the best morning light."),
                    UiStrings.Quotes to page(root, "quote", quote, "An easy weekend route if you’re looking for a short walk."),
                    UiStrings.RepostedBy to people(root, "Avery Chen", "Nora Bell", "Jamie Lin"),
                ),
        )

        choice(UiStrings.Replies).assertIsSelected()
        compose.onNodeWithText(relevant).assertIsDisplayed()
        filter().performClick()
        menuOption(UiStrings.Relevance).assertIsDisplayed()
        menuOption(UiStrings.Recency).assertIsDisplayed()
        menuOption(UiStrings.Likes).assertIsDisplayed()
        screenshot("post-detail-x-sort.png")
        menuOption(UiStrings.Likes).performClick()
        compose.onNodeWithText(liked).assertIsDisplayed()
        compose.onNodeWithText(relevant).assertDoesNotExist()

        choice(UiStrings.Quotes).performClick().assertIsSelected()
        compose.onNodeWithText(quote).assertIsDisplayed()
        filter().assertDoesNotExist()
        screenshot("post-detail-x-quotes.png")

        choice(UiStrings.RepostedBy).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Avery Chen").assertIsDisplayed()
        filter().assertDoesNotExist()
        screenshot("post-detail-x-reposted-by.png")
    }

    @Test
    fun aSingleTabShowsItsFilterWithoutATabLabel() {
        val root = post("VVo", "m.weibo.cn", "single-tab", "林晓雨", "今天的图书馆开到晚上八点。")
        val latest = "刚确认，三楼还有座位。"
        show(
            root = root,
            primaryTab = UiStrings.Comments,
            filterOptions = listOf(UiStrings.ByPopularity, UiStrings.ByTime),
            contents =
                mapOf(
                    UiStrings.ByPopularity to listOf(post("VVo", "m.weibo.cn", "hot", "周柯", "谢谢提醒。")),
                    UiStrings.ByTime to listOf(post("VVo", "m.weibo.cn", "latest", "陈星", latest)),
                ),
        )

        choice(UiStrings.Comments).assertDoesNotExist()
        filter().assertIsDisplayed().performClick()
        menuOption(UiStrings.ByTime).assertIsDisplayed().performClick()
        compose.onNodeWithText(latest).assertIsDisplayed()
    }

    @Test
    fun fourTabsKeepTheThreadHeaderWhenOpeningThePeopleList() {
        val parentText = "Has anyone tried the new path along the river?"
        val rootText = "I rode it this morning. The footbridge is open again."
        val oldestReply = "The west entrance is open, too."
        val root = post("Bluesky", "bsky.social", "bike-trip", "Maya Patel", rootText)
        val profiles = people(root, "Alex Moore", "Sam Bell", "Jamie Lin")
        show(
            root = root,
            parents = listOf(post("Bluesky", "bsky.social", "path-question", "Nora Chen", parentText)),
            primaryTab = UiStrings.Replies,
            secondaryTab = UiStrings.Quotes,
            additionalTabs = listOf(UiStrings.RepostedBy, UiStrings.LikedBy),
            filterOptions = listOf(UiStrings.ByPopularity, UiStrings.Recency, UiStrings.Oldest),
            contents =
                mapOf(
                    UiStrings.ByPopularity to page(root, "top", "The riverside section was great.", "I liked it too."),
                    UiStrings.Recency to page(root, "latest", "I just got back from the ride.", "It's quiet right now."),
                    UiStrings.Oldest to page(root, "oldest", oldestReply, "Good to know."),
                    UiStrings.Quotes to page(root, "quote", "Saving this for Saturday.", "The bridge is back!"),
                    UiStrings.RepostedBy to profiles.take(1),
                    UiStrings.LikedBy to profiles,
                ),
        )

        filter().performClick()
        menuOption(UiStrings.Oldest).assertIsDisplayed()
        screenshot("post-detail-bluesky-reply-sort.png")
        menuOption(UiStrings.Oldest).performClick()
        compose.onNodeWithText(oldestReply).assertIsDisplayed()

        choice(UiStrings.LikedBy).performScrollTo().performClick().assertIsSelected()
        filter().assertDoesNotExist()
        compose.onNodeWithText("Alex Moore").assertIsDisplayed()
        compose.onNodeWithText(parentText).assertIsDisplayed()
        compose.onNodeWithText(rootText).assertIsDisplayed()
        screenshot("post-detail-bluesky-liked-by.png")

        choice(UiStrings.Replies).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText(oldestReply).assertIsDisplayed()
    }

    private fun show(
        root: UiTimelineV2.Post,
        primaryTab: UiStrings,
        secondaryTab: UiStrings? = null,
        filterOptions: List<UiStrings>,
        contents: Map<UiStrings, List<UiTimelineV2>>,
        additionalTabs: List<UiStrings> = emptyList(),
        parents: List<UiTimelineV2> = emptyList(),
        isPostLoaded: () -> Boolean = { true },
    ) {
        val flows = contents.mapValues { (_, posts) -> flowOf(posts) }
        val loading = emptyFlow<List<UiTimelineV2>>()
        val tabOptions = listOfNotNull(primaryTab, secondaryTab) + additionalTabs
        val tabs = tabOptions.map { it.toChoice() }.toImmutableList()
        val sorts = filterOptions.map { it.toChoice() }.toImmutableList()
        val defaultSort = filterOptions.first()
        compose.setContent {
            var activeTab by remember { mutableStateOf(primaryTab) }
            var activeSort by remember { mutableStateOf(defaultSort) }
            val pages = flows.mapValues { (id, flow) -> key(id) { flow.collectPagingState().value } }
            val labels = (tabOptions + filterOptions).associateWith { it.asText().resolveText() }
            SideEffect { renderedLabels = labels }
            val hasSort = activeTab == primaryTab
            val postLoaded = isPostLoaded()
            val activePage = if (postLoaded) pages.getValue(if (hasSort) activeSort else activeTab) else loading.collectPagingState().value
            val state =
                object : StatusContextPresenter.State {
                    override val current: UiState<UiTimelineV2> = if (postLoaded) UiState.Success(root) else UiState.Loading()
                    override val header = if (postLoaded) (parents + root).toImmutableList() else persistentListOf()
                    override val tabs = tabs
                    override val selectedTabId = activeTab.name
                    override val filters = if (hasSort) sorts else persistentListOf()
                    override val selectedFilterId = activeSort.name.takeIf { hasSort }
                    override val defaultFilterId = defaultSort.name.takeIf { hasSort }
                    override val listState = activePage

                    override fun selectTab(id: String) {
                        activeTab = UiStrings.valueOf(id)
                    }

                    override fun selectFilter(id: String) {
                        activeSort = UiStrings.valueOf(id)
                    }

                    override fun refreshAsync() = Unit

                    override suspend fun refresh() = Unit
                }
            CompositionLocalProvider(
                LocalGlobalAppearance provides GlobalAppearance(bottomBarBehavior = BottomBarBehavior.AlwaysShow),
                LocalTimelineAppearance provides TimelineAppearance(showTranslateButton = false),
            ) {
                FlareTheme(darkTheme = false, dynamicColor = false) {
                    StatusDetailScreenContent(root.statusKey, state, onBack = {}, isRefreshing = false, onRefresh = {})
                }
            }
        }
    }

    private fun UiStrings.toChoice() = StatusContextPresenter.Choice(name, asText())

    private fun choice(label: UiStrings) = compose.onNodeWithText(compose.runOnIdle { renderedLabels.getValue(label) })

    private fun filter() = compose.onNodeWithTag("status-detail-filter")

    private fun menuOption(label: UiStrings) =
        compose.onNode(hasText(compose.runOnIdle { renderedLabels.getValue(label) }) and hasAnyAncestor(isPopup()))

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val target = File(requireNotNull(instrumentation.targetContext.externalCacheDir), name)
            target.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun page(
        root: UiTimelineV2.Post,
        id: String,
        first: String,
        second: String,
    ): List<UiTimelineV2> {
        val names = if (root.platformId == "VVo") listOf("周柯", "赵晴", "陈星") else listOf("Alex Moore", "Sam Bell", "Jamie Lin")
        return List(12) { index ->
            val text =
                when (index) {
                    0 -> first
                    1 -> second
                    else -> if (root.platformId == "VVo") "谢谢分享，已经记下这条路线。" else "Thanks for sharing this route."
                }
            post(root.platformId, root.statusKey.host, "$id-$index", names[index % names.size], text)
        }
    }

    private fun post(
        platformId: String,
        host: String,
        id: String,
        name: String,
        text: String,
    ) = UiTimelineV2.Post(
        platformId = platformId,
        images = persistentListOf(),
        sensitive = false,
        contentWarning = null,
        user = profile(platformId, host, name),
        content = UiTranslatableText(text.toUiPlainText()),
        actions =
            persistentListOf(
                ActionMenu.Item(icon = UiIcon.Comment, count = UiNumber(3), actionFamily = PostActionFamily.Comment),
                ActionMenu.Item(icon = UiIcon.Like, count = UiNumber(24), actionFamily = PostActionFamily.Like),
            ),
        poll = null,
        statusKey = MicroBlogKey(id, host),
        card = null,
        createdAt = Instant.parse("2026-11-15T07:00:00Z").toUi(),
        clickEvent = ClickEvent.Noop,
        accountType = AccountType.Specific(MicroBlogKey("test-viewer", host)),
    )

    private fun people(
        root: UiTimelineV2.Post,
        vararg names: String,
    ): List<UiTimelineV2.User> =
        names.map { name ->
            val profile = profile(root.platformId, root.statusKey.host, name)
            UiTimelineV2.User(
                value = profile,
                createdAt = root.createdAt,
                statusKey = profile.key,
                accountType = root.accountType,
            )
        }

    private fun profile(
        platformId: String,
        host: String,
        name: String,
    ) = createSampleUser().copy(
        key = MicroBlogKey(name, host),
        handle = UiHandle(raw = name.lowercase().replace(" ", ""), host = host),
        avatar = null,
        banner = null,
        nameInternal = name.toUiPlainText(),
        platformId = platformId,
        platformIcon =
            when (platformId) {
                "VVo" -> UiIcon.Weibo
                "Bluesky" -> UiIcon.Bluesky
                else -> UiIcon.X
            }.asType(),
    )
}
