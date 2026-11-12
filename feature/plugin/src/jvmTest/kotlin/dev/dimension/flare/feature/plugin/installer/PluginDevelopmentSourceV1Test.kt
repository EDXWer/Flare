package dev.dimension.flare.feature.plugin.installer

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PluginDevelopmentSourceV1Test {
    private lateinit var root: Path
    private val files = FileSystem.SYSTEM

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("plugin-development-source").toOkioPath()
        TestFppFactory.validEntries().forEach { (name, bytes) ->
            val path = root / name
            files.createDirectories(path.parent!!)
            files.write(path) { write(bytes) }
        }
    }

    @AfterTest
    fun teardown() {
        files.deleteRecursively(root)
    }

    @Test
    fun hashesOnlyPluginInputsAndKeepsAnImmutableSnapshot() {
        val first = PluginDevelopmentSourceV1.read(files, root)
        files.write(root / "package.json") { writeUtf8("ignored build metadata") }
        assertEquals(first.hash, PluginDevelopmentSourceV1.read(files, root).hash)
        files.write(root / "plugin.js") { writeUtf8("changed") }
        assertNotEquals(first.hash, PluginDevelopmentSourceV1.read(files, root).hash)
        assertEquals(
            TestFppFactory.validScript,
            first.archive.files
                .getValue("plugin.js")
                .decodeToString(),
        )
        files.createDirectories(root / "locales")
        val scriptHash = PluginDevelopmentSourceV1.read(files, root).hash
        files.write(root / "locales/en.json") { writeUtf8("{}") }
        assertNotEquals(scriptHash, PluginDevelopmentSourceV1.read(files, root).hash)
    }

    @Test
    fun windowsDevelopmentPathsProducePortableSettingsEntries() {
        files.createDirectories(root / "settings/scripts")
        files.write(root / "settings/index.html") { writeUtf8("<html></html>") }
        files.write(root / "settings/scripts/main.js") { writeUtf8("export {};") }
        val expected = PluginDevelopmentSourceV1.read(files, root)
        val windowsRoot = "C:\\plugins\\example".toPath()
        val windowsFiles =
            object : ForwardingFileSystem(files) {
                override fun onPathParameter(
                    path: Path,
                    functionName: String,
                    parameterName: String,
                ): Path = root / path.relativeTo(windowsRoot).segments.joinToString("/")

                override fun onPathResult(
                    path: Path,
                    functionName: String,
                ): Path = windowsRoot / path.relativeTo(root).segments.joinToString("\\")
            }

        val actual = PluginDevelopmentSourceV1.read(windowsFiles, windowsRoot)

        assertEquals(expected.hash, actual.hash)
        assertEquals(expected.archive.entries, actual.archive.entries)
        assertEquals(
            "export {};",
            actual.archive.files
                .getValue("settings/scripts/main.js")
                .decodeToString(),
        )
    }

    @Test
    fun rejectsMissingFilesAndSymlinks() {
        files.delete(root / "plugin.js")
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
        files.write(root / "elsewhere.js") { writeUtf8(TestFppFactory.validScript) }
        files.createSymlink(root / "plugin.js", root / "elsewhere.js")
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
    }

    @Test
    fun extensionOnlyDevelopmentSourceRequiresBackgroundScript() {
        files.write(root / "manifest.json") {
            writeUtf8(
                """
                {
                  "apiVersion": 2, "id": "org.example.extension", "version": "1.0.0", "name": "Test extension",
                  "platformExtension": {
                    "targets": [{"platformId": "TestPlugin", "authOrigins": ["https://example.invalid"]}],
                    "actions": [{"id": "testAction", "title": "Test action"}]
                  }
                }
                """.trimIndent(),
            )
        }
        PluginDevelopmentSourceV1.read(files, root)
        files.delete(root / "plugin.js")
        val error = assertFailsWith<IllegalArgumentException> { PluginDevelopmentSourceV1.read(files, root) }
        assertEquals("Background capabilities require plugin.js", error.message)
    }

    @Test
    fun rejectsOversizedInputsBeforeReadingThem() {
        java.io.RandomAccessFile((root / "manifest.json").toString(), "rw").use { it.setLength(16 * 1024 * 1024) }
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
    }

    @Test
    fun developmentAndArchiveLayoutsShareTheEntryCountLimit() {
        files.createDirectories(root / "settings")
        repeat(FppLimits.MAX_ENTRY_COUNT - TestFppFactory.validEntries().size) { index ->
            files.write(root / "settings/resource$index.js") {}
        }
        val accepted = PluginDevelopmentSourceV1.read(files, root).archive
        assertEquals(FppLimits.MAX_ENTRY_COUNT, accepted.entries.size)
        files.write(root / "settings/extra.js") {}
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
        assertFails {
            FppArchiveReader(files).validateLayout(
                accepted.entries + FppArchiveEntry("settings/extra.js", false, 0, 0, 0),
            )
        }
    }
}
