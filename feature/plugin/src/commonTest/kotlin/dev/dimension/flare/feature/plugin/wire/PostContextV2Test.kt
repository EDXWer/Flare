package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PostContextV2Test {
    private val title = WireTextV1(value = "Replies")
    private val option = PostContextFilterOptionV2("recent", WireTextV1(key = "recent", fallback = "Recent"))
    private val filter = PostContextFilterV2(listOf(option), option.id)
    private val tab = PostContextTabV2("replies", title, filter)

    @Test
    fun acceptsAnEmptyTabListAndValidLocalizedMetadata() {
        val empty = PluginJsonV1.decodeFromString<PostContextTabsV2>("""{"tabs": []}""")
        empty.requireValid()
        assertEquals(PostContextTabsV2(emptyList()), empty)
        PostContextTabsV2(listOf(tab), replyTabId = tab.id).requireValid()
    }

    @Test
    fun rejectsInvalidTabAndFilterMetadata() {
        val invalid =
            listOf(
                PostContextTabsV2(listOf(tab, tab)),
                PostContextTabsV2(listOf(tab), replyTabId = "related"),
                PostContextTabsV2(listOf(tab.copy(id = "bad id"))),
                PostContextTabsV2(listOf(tab.copy(title = WireTextV1(value = "")))),
                PostContextTabsV2(listOf(tab.copy(filter = filter.copy(options = listOf(option, option))))),
                PostContextTabsV2(listOf(tab.copy(filter = filter.copy(options = emptyList())))),
                PostContextTabsV2(listOf(tab.copy(filter = filter.copy(defaultOptionId = "missing")))),
            )
        invalid.forEach { value ->
            assertFailsWith<IllegalArgumentException> { value.requireValid() }
        }
    }

    @Test
    fun pageRequestOmitsAnAbsentFilterId() {
        val request =
            PostContextPageRequestV2(
                key = EntityKeyV1("post/42", "example.social"),
                tabId = tab.id,
                page = PageRequestV1(PageDirectionV1.Refresh, 20),
            )
        request.requireValid()
        val encoded = PluginJsonV1.parseToJsonElement(PluginJsonV1.encodeToString(request)).jsonObject
        assertFalse("filterId" in encoded)
    }
}
