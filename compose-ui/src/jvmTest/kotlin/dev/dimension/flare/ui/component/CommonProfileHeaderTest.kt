package dev.dimension.flare.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.ui.model.UiHandle
import dev.dimension.flare.ui.model.createSampleUser
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.theme.PlatformTheme
import io.github.composefluent.FluentTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class CommonProfileHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profileHeaderShowsALargerBoldNameAndUpdatesTheDisplayHandle() {
        val user = createSampleUser()
        val name = "Alex Rivera"
        var handle by mutableStateOf(UiHandle("bsky.app", "bsky.social", displayOverride = "@bsky.app"))
        var expectedSize = 0f

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(LocalTimelineAppearance provides TimelineAppearance()) {
                    expectedSize = PlatformTheme.typography.title.fontSize.value + 2
                    CommonProfileHeader(
                        banner = null,
                        avatar = null,
                        displayName = name.toUiPlainText(),
                        userKey = user.key,
                        handle = handle,
                    )
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val style = layouts.single().layoutInput.style
        assertEquals(expectedSize, style.fontSize.value)
        assertEquals(FontWeight.Bold, style.fontWeight)
        compose.onNodeWithText("@bsky.app").assertIsDisplayed()
        compose.onNodeWithText("@bsky.app@bsky.social").assertDoesNotExist()
        compose.runOnIdle { handle = handle.copy(displayOverride = "@updated.example") }
        compose.onNodeWithText("@updated.example").assertIsDisplayed()
        compose.onNodeWithText("@bsky.app").assertDoesNotExist()
    }

    @Test
    fun plainProfileDetailsUseOnlyTheContentInset() {
        val user = createSampleUser()
        var useCard by mutableStateOf(true)

        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(LocalTimelineAppearance provides TimelineAppearance()) {
                    CommonProfileHeader(
                        banner = null,
                        avatar = null,
                        displayName = user.name,
                        userKey = user.key,
                        handle = UiHandle("reader", "example.org", displayOverride = "@reader"),
                        useCard = useCard,
                    )
                }
            }
        }

        compose.onNodeWithText("@reader").assertLeftPositionInRootIsEqualTo(32.dp)
        compose.runOnIdle { useCard = false }
        compose.onNodeWithText("@reader").assertLeftPositionInRootIsEqualTo(16.dp)
    }
}
