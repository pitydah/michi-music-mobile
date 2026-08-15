package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiverSessionCreateRequest(
    val transport: String = "rtp_udp",
    val codec: String = "pcm_s16le",
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    val channels: Int = 2,
    @SerialName("packet_ms") val packetMs: Int = 10,
    @SerialName("buffer_ms") val bufferMs: Int = 120,
    @SerialName("payload_type") val payloadType: Int = 97,
    val ssrc: Long = (1L..4294967294L).random(),
    val volume: Int = 70,
)

@Serializable
data class ReceiverSessionEffectiveDto(
    val transport: String = "rtp_udp",
    val codec: String = "pcm_s16le",
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    val channels: Int = 2,
    @SerialName("packet_ms") val packetMs: Int = 10,
    @SerialName("buffer_ms") val bufferMs: Int = 120,
    @SerialName("payload_type") val payloadType: Int = 97,
    val ssrc: Long = 0L,
    @SerialName("stream_port") val streamPort: Int = 0,
    val volume: Int = 70,
)

@Serializable
data class ReceiverSessionCreateResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_token") val sessionToken: String,
    @SerialName("lease_seconds") val leaseSeconds: Int = 30,
    val effective: ReceiverSessionEffectiveDto,
)

@Serializable
data class ReceiverSessionStateDto(
    @SerialName("session_id") val sessionId: String = "",
    val state: String = "active",
    val volume: Int = 70,
    val paused: Boolean = false,
    val effective: ReceiverSessionEffectiveDto? = null,
)

@Serializable
data class ReceiverSessionPatchRequest(
    val volume: Int? = null,
    val paused: Boolean? = null,
)

@Serializable
data class ReceiverHeartbeatRequest(
    val sequence: Long,
    val timestamp: Long = System.currentTimeMillis() / 1000,
)

@Serializable
data class ReceiverHeartbeatResponse(
    @SerialName("lease_seconds") val leaseSeconds: Int = 30,
    @SerialName("server_time") val serverTime: Long? = null,
    @SerialName("next_action") val nextAction: String? = null,
)
