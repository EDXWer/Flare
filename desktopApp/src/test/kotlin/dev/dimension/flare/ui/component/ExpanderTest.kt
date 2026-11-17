package dev.dimension.flare.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.dimension.flare.ui.theme.FlareTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ExpanderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wrappedCaptionsKeepTrailingActionsClickableAndAlignedAcrossExpanderVariants() {
        var expanded by mutableStateOf(true)
        var expanderActionClicks = 0
        var itemActionClicks = 0
        var cardActionClicks = 0
        val longCaption =
            "A long multi-line setting description that wraps inside narrow panes without pushing trailing actions or chevrons off screen."

        compose.setContent {
            FlareTheme(isDarkTheme = false) {
                Column(Modifier.width(360.dp)) {
                    Expander(
                        expanded = expanded,
                        onExpandedChanged = { expanded = it },
                        heading = { Text("Group setting") },
                        caption = { Text(longCaption) },
                        trailing = {
                            Button(onClick = { expanderActionClicks++ }) {
                                Text("Group action")
                            }
                        },
                    ) {
                        ExpanderItem(
                            heading = { Text("Child setting") },
                            caption = { Text(longCaption) },
                            trailing = {
                                Button(onClick = { itemActionClicks++ }) {
                                    Text("Child action")
                                }
                            },
                        )
                    }
                    CardExpanderItem(
                        heading = { Text("Standalone card setting") },
                        caption = { Text(longCaption) },
                        trailing = {
                            Button(onClick = { cardActionClicks++ }) {
                                Text("Card action")
                            }
                        },
                    )
                }
            }
        }

        val groupActionBounds = compose.onNodeWithText("Group action").fetchSemanticsNode().boundsInRoot
        val childActionBounds = compose.onNodeWithText("Child action").fetchSemanticsNode().boundsInRoot
        assertTrue("Expected positive width for Expander trailing action, got $groupActionBounds", groupActionBounds.width > 0f)
        assertEquals(groupActionBounds.right, childActionBounds.right, 1f)

        compose.onNodeWithText("Group action").performClick()
        compose.onNodeWithText("Child action").performClick()
        compose.onNodeWithText("Card action").performClick()
        compose.onNodeWithText("Group setting").performClick()
        compose.runOnIdle {
            assertEquals(1, expanderActionClicks)
            assertEquals(1, itemActionClicks)
            assertEquals(1, cardActionClicks)
            assertFalse(expanded)
        }
    }
}
