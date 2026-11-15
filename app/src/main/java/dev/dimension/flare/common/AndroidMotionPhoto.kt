package dev.dimension.flare.common

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

internal fun reserveMotionPhotoFile(file: File): File =
    if (file.createNewFile()) {
        file
    } else {
        val name = file.name.removeSuffix("_MP.jpg").padEnd(3, '_')
        File.createTempFile("$name-", "_MP.jpg", file.parentFile)
    }

// https://developer.android.com/media/platform/motion-photo-format
internal fun writeMotionPhoto(
    cover: File,
    video: File,
    output: OutputStream,
) {
    RandomAccessFile(cover, "r").use { jpeg ->
        require(jpeg.length() >= 4 && jpeg.readUnsignedShort() == 0xffd8) { "Expected a JPEG cover" }
        jpeg.seek(jpeg.length() - 2)
        require(jpeg.readUnsignedShort() == 0xffd9) { "Incomplete JPEG cover" }
    }
    val videoMimeType =
        DataInputStream(video.inputStream().buffered()).use { clip ->
            val headerLength = clip.readInt()
            require(headerLength in 16L..video.length() && clip.readInt() == 0x66747970) { "Expected a MOV or MP4 clip" }
            if (clip.readInt() == 0x71742020) "video/quicktime" else "video/mp4"
        }
    // shortcut: The viewer uses the middle frame; read Apple's timed metadata if exact still timing is needed.
    val xmp =
        (
            "http://ns.adobe.com/xap/1.0/\u0000" +
                """
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                        xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                        xmlns:Container="http://ns.google.com/photos/1.0/container/"
                        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                        Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1">
                      <Container:Directory><rdf:Seq>
                        <rdf:li rdf:parseType="Resource">
                          <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>
                        </rdf:li>
                        <rdf:li rdf:parseType="Resource">
                          <Container:Item Item:Mime="$videoMimeType" Item:Semantic="MotionPhoto" Item:Length="${video.length()}"/>
                        </rdf:li>
                      </rdf:Seq></Container:Directory>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                """.trimIndent()
        ).encodeToByteArray()
    val destination = DataOutputStream(output)
    DataInputStream(cover.inputStream().buffered()).use { jpeg ->
        destination.writeShort(jpeg.readUnsignedShort())
        val firstMarker = jpeg.readUnsignedShort()
        // JFIF expects its APP0 marker directly after SOI.
        if (firstMarker == 0xffe0) {
            val length = jpeg.readUnsignedShort()
            require(length >= 2) { "Invalid JPEG APP0 length" }
            destination.writeShort(firstMarker)
            destination.writeShort(length)
            destination.write(ByteArray(length - 2).also(jpeg::readFully))
        }
        // shortcut: Weibo covers are standalone JPEGs; merge source XMP if the CDN starts adding it.
        destination.writeShort(0xffe1)
        destination.writeShort(xmp.size + 2)
        destination.write(xmp)
        if (firstMarker != 0xffe0) destination.writeShort(firstMarker)
        jpeg.copyTo(destination)
    }
    video.inputStream().use { it.copyTo(destination) }
}
