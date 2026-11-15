package dev.dimension.flare.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File

class AndroidMotionPhotoTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun embedsTheOriginalJpegAndQuickTimeWithAnExactVideoLength() {
        val cover = byteArrayOf(-1, -40, -1, -32, 0, 4, 1, 2, -1, -39)
        val video = byteArrayOf(0, 0, 0, 16) + "ftypqt  qt  ".toByteArray()
        val output = ByteArrayOutputStream()

        writeMotionPhoto(
            folder.newFile().apply { writeBytes(cover) },
            folder.newFile().apply { writeBytes(video) },
            output,
        )

        val result = output.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(result, 8, result.size - 8))
        assertEquals(0xffe1, input.readUnsignedShort())
        val xmp = String(ByteArray(input.readUnsignedShort() - 2).also(input::readFully))
        assertTrue(xmp.startsWith("http://ns.adobe.com/xap/1.0/\u0000"))
        assertTrue(xmp.contains("Camera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("Item:Mime=\"video/quicktime\" Item:Semantic=\"MotionPhoto\" Item:Length=\"${video.size}\""))
        assertArrayEquals(cover, result.copyOfRange(0, 8) + input.readNBytes(2))
        assertArrayEquals(video, input.readBytes())
    }

    @Test
    fun rejectsAnInvalidCoverBeforeWriting() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            writeMotionPhoto(folder.newFile(), folder.newFile(), output)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun reservesAnotherMotionPhotoNameWhenTheOriginalExists() {
        val requested = File(folder.root, "a_MP.jpg")
        assertEquals(requested, reserveMotionPhotoFile(requested))
        requested.writeText("original")

        val duplicate = reserveMotionPhotoFile(requested)

        assertNotEquals(requested, duplicate)
        assertTrue(duplicate.isFile)
        assertTrue(duplicate.name.endsWith("_MP.jpg"))
        assertEquals("original", requested.readText())
    }
}
