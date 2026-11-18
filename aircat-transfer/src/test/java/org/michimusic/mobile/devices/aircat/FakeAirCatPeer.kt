package org.michimusic.mobile.devices.aircat

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.michimusic.link.LinkClient
import org.michimusic.link.StreamRangeResult
import java.io.OutputStream

class FakeAirCatPeer(
    val deviceId: String = "fake_aircat_peer_1",
    val capabilities: AirCatCapabilities = AirCatCapabilities(),
    val mockClient: LinkClient = mockk(),
) {
    fun setupTrackStreaming(trackId: String, content: ByteArray, etag: String = "test-etag") {
        every {
            runBlocking {
                mockClient.streamTrackRange(
                    trackId = trackId,
                    outputStream = any(),
                    startBytes = any(),
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val os = secondArg<OutputStream>()
            val start = thirdArg<Long>().toInt()
            val slice = content.sliceArray(start until content.size)
            os.write(slice)
            Result.success(
                StreamRangeResult(
                    bytesRead = slice.size.toLong(),
                    statusCode = if (start > 0) 206 else 200,
                    isPartialContent = start > 0,
                    etag = etag,
                    contentLength = slice.size.toLong(),
                )
            )
        }
    }
}
