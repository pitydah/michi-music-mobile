package org.michimusic.mobile.ui.seeker

import java.nio.ByteBuffer

/** Signed little-endian 24-bit PCM, normalized for VISUAL amplitude only. */
internal fun readPcm24Sample(buffer: ByteBuffer): Float {
    val low = buffer.get().toInt() and 0xff
    val middle = buffer.get().toInt() and 0xff
    val high = buffer.get().toInt() and 0xff
    val raw = low or (middle shl 8) or (high shl 16)
    val signed = if ((raw and 0x800000) != 0) raw or -0x1000000 else raw
    return signed / 8_388_608f
}
