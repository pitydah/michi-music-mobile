package org.michimusic.link.rtp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.michimusic.link.LinkClient
import org.michimusic.link.dto.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SimulatedReceiverLiteE2ETest {

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var httpPort = 0
    private var udpPort = 0

    @Volatile
    private var lastHeartbeatSessionHeader: String? = null
    @Volatile
    private var sessionDeleted = false

    @Before
    fun setUp() {
        // 1. Setup UDP RTP receiver socket
        udpSocket = DatagramSocket(0)
        udpPort = udpSocket!!.localPort

        // 2. Setup embedded HTTP server using raw ServerSocket
        serverSocket = ServerSocket(0)
        httpPort = serverSocket!!.localPort

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && serverSocket?.isClosed == false) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleHttpClient(clientSocket) }
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            var headerLine = reader.readLine()
            var xMichiSession: String? = null
            var contentLength = 0
            while (!headerLine.isNullOrEmpty()) {
                val colonIdx = headerLine.indexOf(":")
                if (colonIdx > 0) {
                    val key = headerLine.substring(0, colonIdx).trim()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    if (key.equals("X-Michi-Session", ignoreCase = true)) {
                        xMichiSession = value
                    } else if (key.equals("Content-Length", ignoreCase = true)) {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                headerLine = reader.readLine()
            }

            // Read body if present
            if (contentLength > 0) {
                val bodyChars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(bodyChars, read, contentLength - read)
                    if (r < 0) break
                    read += r
                }
            }

            val out = s.getOutputStream()
            when {
                path.startsWith("/api/v1/server/info") -> {
                    val body = """
                        {
                            "server": "michi_stream_mock",
                            "version": "1.0.0",
                            "api_version": "v1-lite",
                            "michi_link_version": "1.0",
                            "roles": ["audio_receiver"],
                            "audio": {
                                "transports": ["rtp_udp"],
                                "codecs": ["pcm_s16le"],
                                "sample_rates": [44100, 48000],
                                "bit_depths": [16],
                                "channels": [2],
                                "packet_ms": [10],
                                "payload_types": [97]
                            }
                        }
                    """.trimIndent()
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray())
                }
                path.startsWith("/api/v1/receiver-lite/session") && method == "POST" -> {
                    val body = """
                        {
                            "session_id": "sim_session_123",
                            "session_token": "sim_tok_abc",
                            "lease_seconds": 30,
                            "effective": {
                                "transport": "rtp_udp",
                                "codec": "pcm_s16le",
                                "sample_rate": 48000,
                                "bit_depth": 16,
                                "channels": 2,
                                "packet_ms": 10,
                                "buffer_ms": 120,
                                "payload_type": 97,
                                "ssrc": 999999,
                                "stream_port": $udpPort,
                                "volume": 70
                            }
                        }
                    """.trimIndent()
                    val response = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray())
                }
                path.startsWith("/api/v1/receiver-lite/session") && method == "PATCH" -> {
                    val body = "{}"
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray())
                }
                path.startsWith("/api/v1/receiver-lite/session") && method == "DELETE" -> {
                    sessionDeleted = true
                    val body = "{}"
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray())
                }
                path.startsWith("/api/v1/receiver-lite/heartbeat") -> {
                    lastHeartbeatSessionHeader = xMichiSession
                    val body = """{"status": "ok", "lease_seconds": 30}"""
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray())
                }
                else -> {
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(response.toByteArray())
                }
            }
            out.flush()
        }
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        serverJob?.cancel()
        udpSocket?.close()
    }

    @Test
    fun completeNetworkE2E_simulatedReceiverLifecycleAndRtpStreaming() = runTest {
        val client = LinkClient("http://127.0.0.1:$httpPort")

        // 1. Query ServerInfo
        val serverInfoRes = client.getServerInfo()
        assertTrue("ServerInfo query must succeed", serverInfoRes.isSuccess)
        val sInfo = serverInfoRes.getOrThrow()
        assertEquals("michi_stream_mock", sInfo.server)
        assertEquals("v1-lite", sInfo.apiVersion)

        // 2. Create ReceiverLite Session
        val req = ReceiverSessionCreateRequest(
            transport = "rtp_udp",
            codec = "pcm_s16le",
            sampleRate = 48000,
            bitDepth = 16,
            channels = 2,
            packetMs = 10,
            volume = 70
        )
        val sessionRes = client.createReceiverLiteSession(req)
        assertTrue("Session creation must succeed", sessionRes.isSuccess)
        val session = sessionRes.getOrThrow()
        assertEquals("sim_session_123", session.sessionId)
        assertEquals("sim_tok_abc", session.sessionToken)
        assertEquals(udpPort, session.effective.streamPort)

        // 3. Send Heartbeat with X-Michi-Session header verification
        val hbRes = client.sendReceiverLiteHeartbeat(
            sessionId = session.sessionId,
            sequence = 1L,
            sessionToken = session.sessionToken
        )
        assertTrue("Heartbeat must succeed", hbRes.isSuccess)
        assertEquals("sim_tok_abc", lastHeartbeatSessionHeader)

        // 4. Start RTP Audio Sender and push actual PCM audio chunks over UDP
        val sender = RtpAudioSender()
        val senderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sender.start("127.0.0.1", session.effective, senderScope)
        assertTrue(sender.isActive)

        // 10ms of 48kHz 16-bit stereo = 480 samples * 4 bytes = 1920 bytes
        val chunk1 = ByteArray(1920) { (it % 100).toByte() }
        val chunk2 = ByteArray(1920) { ((it + 1) % 100).toByte() }
        sender.sendPcmChunk(chunk1)
        sender.sendPcmChunk(chunk2)

        // Read UDP packets from the socket receiver
        val buf = ByteArray(2048)
        val packet1 = DatagramPacket(buf, buf.size)
        udpSocket!!.soTimeout = 3000
        udpSocket!!.receive(packet1)

        assertEquals("Packet length must be 12 (header) + 1920 (payload)", 1932, packet1.length)
        val bb1 = ByteBuffer.wrap(packet1.data, 0, packet1.length).order(ByteOrder.BIG_ENDIAN)
        val v1 = (bb1.get().toInt() and 0xFF) ushr 6
        val pt1 = bb1.get().toInt() and 0x7F
        val seq1 = bb1.short.toInt() and 0xFFFF
        val ts1 = bb1.int.toLong() and 0xFFFFFFFFL
        val ssrc1 = bb1.int.toLong() and 0xFFFFFFFFL

        assertEquals(2, v1) // RTP version 2
        assertEquals(97, pt1) // Payload type 97
        assertEquals(999999L, ssrc1) // SSRC matches effective
        assertTrue("Initial timestamp is a valid 32-bit unsigned integer", ts1 in 0L..0xFFFFFFFFL)

        val packet2 = DatagramPacket(buf, buf.size)
        udpSocket!!.receive(packet2)
        val bb2 = ByteBuffer.wrap(packet2.data, 0, packet2.length).order(ByteOrder.BIG_ENDIAN)
        bb2.get()
        bb2.get()
        val seq2 = bb2.short.toInt() and 0xFFFF
        val ts2 = bb2.int.toLong() and 0xFFFFFFFFL

        assertEquals((seq1 + 1) and 0xFFFF, seq2) // Monotonic sequence
        assertEquals((ts1 + 480) and 0xFFFFFFFFL, ts2) // Exactly 480 samples timestamp advance

        // 5. Teardown session
        sender.stop()
        senderScope.cancel()

        val deleteRes = client.deleteReceiverLiteSession(session.sessionToken)
        assertTrue("Session deletion must succeed", deleteRes.isSuccess)
        assertTrue("Server must have recorded DELETE call", sessionDeleted)
    }
}
