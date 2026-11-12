package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeV2Test {
    @Test
    fun oldReadonlyLoginConfigStaysValidWithoutGrantingPublish() {
        val success =
            PluginJsonV1.decodeFromString<LoginSuccessV1>(
                """{
              "accountId":"me","origin":"https://example.com","credential":{},
              "profile":{"key":{"id":"me","host":"example.com"},"handle":"me","displayName":"Me"},
              "capabilities":{"flare.datasource.timeline/v1":["page"]},
              "composeConfig":{"text":{"maxLength":500},"contentWarning":true}
            }""",
            )
        success.requireValid()
        assertEquals(setOf("flare.datasource.timeline/v1"), success.capabilities.keys)
        assertFailsWith<IllegalArgumentException> {
            success.copy(composeConfig = ComposeConfigV2(text = ComposeConfigV2.TextConfigV1(-1))).requireValid()
        }
    }

    @Test
    fun legacyRequestsPreserveRepliesAndCannotPublishQuotes() {
        val key = EntityKeyV1("opaque/42", "remote.example")
        val newPost = ComposeRequestV2("new", VisibilityV1.Public)
        val plain = PluginJsonV1.parseToJsonElement(PluginJsonV1.encodeToString(LegacyComposeRequestV1Serializer, newPost)).jsonObject
        assertFalse("replyTo" in plain || "reference" in plain)
        val reply =
            PluginJsonV1
                .parseToJsonElement(
                    PluginJsonV1.encodeToString(LegacyComposeRequestV1Serializer, newPost.copy(reference = ComposeReferenceV2.Reply(key))),
                ).jsonObject
        assertEquals(
            "opaque/42",
            reply
                .getValue("replyTo")
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        assertFalse("reference" in reply)
        assertFailsWith<IllegalArgumentException> {
            PluginJsonV1.encodeToString(LegacyComposeRequestV1Serializer, newPost.copy(reference = ComposeReferenceV2.Quote(key)))
        }
    }

    @Test
    fun referenceHasOneTypedTargetAndPreservesOpaqueKeys() {
        val key = EntityKeyV1("opaque/id:42", "remote.example")
        listOf(ComposeReferenceV2.Reply(key), ComposeReferenceV2.Quote(key)).forEach { reference ->
            val request = ComposeRequestV2("caption", VisibilityV1.Public, reference = reference)
            val encoded = PluginJsonV1.encodeToString(ComposeRequestV2.serializer(), request)
            val json = PluginJsonV1.parseToJsonElement(encoded).jsonObject
            assertFalse("replyTo" in json || "quoteTo" in json)
            val type = if (reference is ComposeReferenceV2.Reply) "reply" else "quote"
            assertEquals(
                type,
                json
                    .getValue("reference")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(request, PluginJsonV1.decodeFromString<ComposeRequestV2>(encoded))
        }
        assertFailsWith<SerializationException> {
            PluginJsonV1.decodeFromString<ComposeRequestV2>(
                """{"text":"caption","visibility":"public","reference":{"type":"new","key":{"id":"42","host":"remote.example"}}}""",
            )
        }
        assertFailsWith<SerializationException> {
            PluginJsonV1.decodeFromString<ComposeRequestV2>("""{"text":"caption","visibility":"public","reference":{"type":"quote"}}""")
        }
    }

    @Test
    fun v2ModesStayRequiredWhileLegacyNegotiatedComposeIsAccepted() {
        assertFailsWith<IllegalArgumentException> {
            PluginJsonV1.decodeFromString<ComposeConfigV2>("""{"text":{"maxLength":100}}""").requireValid()
        }
        assertFailsWith<IllegalArgumentException> {
            PluginJsonV1
                .decodeFromString<ComposeConfigV2>(
                    """{"supportedTypes":[]}""",
                ).requireValid()
        }
        val success =
            PluginJsonV1.decodeFromString<LoginSuccessV1>(
                """{
              "accountId":"me","origin":"https://example.com","credential":{},
              "profile":{"key":{"id":"me","host":"example.com"},"handle":"me","displayName":"Me"},
              "capabilities":{"flare.datasource.compose/v1":["publish"]}
            }""",
            )
        success.requireValid()
        assertEquals(setOf("publish"), success.capabilities["flare.datasource.compose/v1"])
    }
}
