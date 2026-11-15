package dev.dimension.flare.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.fleeksoft.ksoup.Ksoup
import dev.dimension.flare.ui.component.platform.PlatformTextStyle
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import io.github.composefluent.FluentTheme
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class RichTextLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun profileBodyUsesCompactSpacingInsideParagraphs() {
        val field = Ksoup.parse("<p>First line<br><a href=\"https://example.test\">第二行</a></p><p>Second paragraph</p>").body().toUi()
        compose.setContent {
            FluentTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    PlatformTextStyle provides TextStyle(fontSize = 14.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
                ) {
                    Column {
                        UserFields(fields = persistentMapOf("About" to field))
                        RichText(text = "Display name".toUiPlainText(), maxLines = 1)
                    }
                }
            }
        }

        val paragraph = compose.onNodeWithText("First line", substring = true, useUnmergedTree = true)
        val nextParagraph = compose.onNodeWithText("Second paragraph", useUnmergedTree = true)
        val name = compose.onNodeWithText("Display name", useUnmergedTree = true)
        val layout = paragraph.textLayout()
        assertEquals(2, layout.lineCount)
        assertEquals(22.sp, layout.layoutInput.style.lineHeight)
        assertEquals(0.sp, layout.layoutInput.style.letterSpacing)
        assertEquals(14.sp, layout.layoutInput.style.fontSize)
        val text = layout.layoutInput.text
        val linkStart = text.text.indexOf("第二行")
        assertEquals(
            0.sp,
            text.spanStyles
                .last { linkStart in it.start until it.end }
                .item.letterSpacing,
        )
        assertEquals(
            0.5.sp,
            name
                .textLayout()
                .layoutInput.style.letterSpacing,
        )
        assertEquals(12f, nextParagraph.fetchSemanticsNode().boundsInRoot.top - paragraph.fetchSemanticsNode().boundsInRoot.bottom, 0.1f)
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
