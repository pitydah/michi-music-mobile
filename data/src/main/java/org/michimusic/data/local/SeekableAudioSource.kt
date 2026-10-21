package org.michimusic.data.local

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

interface SeekableAudioSource : AutoCloseable {
    fun size(): Long?
    fun position(): Long
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun read(buffer: ByteArray): Int = read(buffer, 0, buffer.size)
    fun readByte(): Int {
        val b = ByteArray(1)
        val read = read(b, 0, 1)
        return if (read <= 0) -1 else b[0].toInt() and 0xFF
    }
    fun skipBytes(n: Int): Int {
        if (n <= 0) return 0
        val target = position() + n
        val s = size()
        val dest = if (s != null) target.coerceAtMost(s) else target
        seek(dest)
        return (dest - (target - n)).toInt()
    }
}

class FileSeekableSource(file: File) : SeekableAudioSource {
    private val raf: RandomAccessFile = RandomAccessFile(file, "r")

    override fun size(): Long = raf.length()
    override fun position(): Long = raf.filePointer
    override fun seek(position: Long) = raf.seek(position)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        raf.read(buffer, offset, length)

    override fun close() = raf.close()
}

class ContentUriSeekableSource(
    contentResolver: ContentResolver,
    uri: Uri,
) : SeekableAudioSource {
    private val pfd = contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException("Could not open ParcelFileDescriptor for $uri")
    private val fileChannel: FileChannel = java.io.FileInputStream(pfd.fileDescriptor).channel

    override fun size(): Long = fileChannel.size()
    override fun position(): Long = fileChannel.position()
    override fun seek(position: Long) {
        fileChannel.position(position)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
        return fileChannel.read(byteBuffer)
    }

    override fun close() {
        try {
            fileChannel.close()
        } finally {
            pfd.close()
        }
    }
}

class ByteArraySeekableSource(private val bytes: ByteArray) : SeekableAudioSource {
    private var pos: Int = 0

    override fun size(): Long = bytes.size.toLong()
    override fun position(): Long = pos.toLong()
    override fun seek(position: Long) {
        pos = position.coerceIn(0L, bytes.size.toLong()).toInt()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= bytes.size) return -1
        val available = bytes.size - pos
        val toRead = length.coerceAtMost(available)
        System.arraycopy(bytes, pos, buffer, offset, toRead)
        pos += toRead
        return toRead
    }

    override fun close() {}
}
